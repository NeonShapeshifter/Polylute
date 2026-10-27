#!/usr/bin/env python3
"""Native code finds Java by name; a minified release must not have renamed any of it (B40-F9a).

The 1.0.0 release crashed on its first FLAC. `flac_jni.cc` asks the JVM for `FlacDecoderJni.read(ByteBuffer)` with `GetMethodID(cls, "read",
"(Ljava/nio/ByteBuffer;)I")`; R8, which cannot read C++, had renamed the method, and the lookup failed at the first song. Nothing in the unit tests,
the lint or the release-surface check could see it, because all of them look at Java.

This reads the native sources and the release the other way round. For everything native code names, it checks that it is still there under that name:

  * every `FindClass("a/b/C")` target;
  * every `GetMethodID` / `GetStaticMethodID` / `GetFieldID` / `GetStaticFieldID` (class, name, signature), the class being the one a `FindClass`
    assigned to the variable, or, for `GetObjectClass(thiz)`, the class of the `Java_<class>_...` functions of the file;
  * every `Java_<class>_<method>` function the libraries export (the JVM binds a `native` method to it by the class and method name): the class
    and the method have to be in the release as such.

and fails when something is missing from the dex or when R8's `mapping.txt` says it was renamed. A class of the platform (`java/`, `android/`...)
cannot be renamed and is not looked for. Run it on the artifact that ships, with its mapping:

    check_jni_keeps.py app/build/outputs/bundle/release/app-release.aab --mapping app/build/outputs/mapping/release/mapping.txt

The default native roots are every `src/main/jni` and `src/main/cpp` of `decent-player/libs` and of `app`; `--native` replaces them. Without a
mapping only the dex is read (a rename still shows: the original name is not there).

Exit status 0 when every name is where native code looks for it, 1 when one is not, 2 when there was nothing to check (no dex, no lookups).
"""
import argparse
import collections
import pathlib
import re
import struct
import sys
import zipfile

PLATFORM_PREFIXES = ("java/", "javax/", "android/", "dalvik/", "libcore/", "sun/", "org/w3c/", "org/xml/", "org/json/")
NATIVE_SUFFIXES = (".cc", ".cpp", ".c", ".h", ".hpp", ".cxx")
LOOKUPS = ("FindClass", "GetMethodID", "GetStaticMethodID", "GetFieldID", "GetStaticFieldID")


# ---------------------------------------------------------------------------------------------------------------------------------------------
# the native sources
# ---------------------------------------------------------------------------------------------------------------------------------------------

Lookup = collections.namedtuple("Lookup", "kind cls name sig where")   # kind: class | method | field; cls None when it cannot be told
Export = collections.namedtuple("Export", "cls method where")          # method None for a macro that only fixes the class


def strip_comments(text):
    """The source without `//` and `/* */` comments (string literals are left as they are)."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            out.append("\n" * text.count("\n", i, n if j < 0 else j))
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def split_call(text, open_paren):
    """The top-level arguments of the call whose `(` is at [open_paren], as source text, and the index after its `)`."""
    args = []
    depth = 0
    i = open_paren
    start = open_paren + 1
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append(text[start:i].strip())
                return args, i + 1
        elif c == "," and depth == 1:
            args.append(text[start:i].strip())
            start = i + 1
        i += 1
    return args, n


def literal(arg):
    """The value of a C string literal, or of several next to each other (they are one), or None when [arg] is anything else."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', arg)
    rest = re.sub(r'"(?:[^"\\]|\\.)*"', "", arg)
    if not parts or rest.strip():
        return None
    return "".join(parts).replace('\\"', '"').replace("\\\\", "\\")


def demangle(mangled):
    """The text a JNI-mangled name stands for: `_` is `/`, `_1` is `_`, `_2` is `;`, `_3` is `[`, `_0xxxx` is the character xxxx."""
    out = []
    i = 0
    n = len(mangled)
    while i < n:
        c = mangled[i]
        if c != "_":
            out.append(c)
            i += 1
            continue
        nxt = mangled[i + 1] if i + 1 < n else ""
        if nxt == "1":
            out.append("_")
            i += 2
        elif nxt == "2":
            out.append(";")
            i += 2
        elif nxt == "3":
            out.append("[")
            i += 2
        elif nxt == "0" and re.fullmatch(r"[0-9a-fA-F]{4}", mangled[i + 2:i + 6] or ""):
            out.append(chr(int(mangled[i + 2:i + 6], 16)))
            i += 6
        else:
            out.append("/")
            i += 1
    return "".join(out)


def parse_export(symbol, where):
    """An [Export] for `Java_<class>_<method>` (the method after the last `/`), or for a macro's `Java_<class>_##NAME`."""
    macro = symbol.endswith("_##NAME") or "##" in symbol
    body = symbol[len("Java_"):]
    if macro:
        body = body.split("##")[0].rstrip("_")
        return Export(demangle(body), None, where)
    body = body.split("__")[0] if "__" in body else body   # an overloaded native method carries its signature after `__`
    path = demangle(body)
    if "/" not in path:
        return None
    cls, _, method = path.rpartition("/")
    return Export(cls, method, where)


def scan_source(path):
    """([Lookup], [Export]) of one native source file."""
    raw = pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    text = strip_comments(raw)
    where = str(path)
    exports = []
    seen = set()
    for m in re.finditer(r"\bJava_[A-Za-z0-9_]+(?:##\w+)?", text):
        export = parse_export(m.group(0), where)
        if export is not None and (export.cls, export.method) not in seen:
            seen.add((export.cls, export.method))
            exports.append(export)
    # a macro that builds the exported names (`DECODER_FUNC(jlong, flacInit)` -> Java_<class>_flacInit): each use names a method
    for macro in re.finditer(r"^[ \t]*#[ \t]*define[ \t]+(\w+)[ \t]*\(([^)]*)\)((?:[^\n]*\\\n)*[^\n]*)", text, re.M):
        name, params, body = macro.group(1), [p.strip() for p in macro.group(2).split(",")], macro.group(3)
        built = re.search(r"\bJava_([A-Za-z0-9_]+?)_?##(\w+)", body)
        if not built or built.group(2) not in params:
            continue
        slot = params.index(built.group(2))
        cls = demangle(built.group(1))
        for use in re.finditer(r"^(?!\s*#)[^\n]*?\b%s\s*\(" % re.escape(name), text, re.M):
            args, _ = split_call(text, use.end() - 1)
            if len(args) > slot and re.fullmatch(r"\w+", args[slot]):
                line = text.count("\n", 0, use.start()) + 1
                exports.append(Export(cls, args[slot], f"{where}:{line}"))
    exports = [e for e in exports if e.method is not None or not any(o.cls == e.cls and o.method is not None for o in exports)]
    file_classes = {e.cls for e in exports}
    object_class = next(iter(file_classes)) if len(file_classes) == 1 else None

    variables = {}   # the class each jclass variable holds, as of the line being read
    lookups = []
    for m in re.finditer(r"(?:->|\.)\s*(FindClass|GetObjectClass|GetMethodID|GetStaticMethodID|GetFieldID|GetStaticFieldID)\s*\(", text):
        kind = m.group(1)
        args, _ = split_call(text, m.end() - 1)
        line = text.count("\n", 0, m.start()) + 1
        loc = f"{where}:{line}"
        # the variable the call is assigned to, if it is (`jclass c = env->FindClass(...)`): what stands before the call in its statement
        statement = re.split(r"[;{}]", text[max(0, m.start() - 200):m.start()])[-1]
        assigned = re.search(r"(\w+)\s*=\s*\w+\s*$", statement)
        var = assigned.group(1) if assigned else None
        if kind == "FindClass":
            name = literal(args[0]) if args else None
            if name:
                lookups.append(Lookup("class", name, None, None, loc))
            if var:
                variables[var] = name
        elif kind == "GetObjectClass":
            if var:
                variables[var] = object_class
        else:
            if len(args) < 3:
                continue
            name, sig = literal(args[1]), literal(args[2])
            if name is None or sig is None:
                continue
            what = "field" if "Field" in kind else "method"
            lookups.append(Lookup(what, variables.get(args[0].strip()), name, sig, loc))
    return lookups, exports


def native_files(roots):
    for root in roots:
        root = pathlib.Path(root)
        if root.is_file():
            yield root
        elif root.is_dir():
            for path in sorted(root.rglob("*")):
                if path.is_file() and path.suffix in NATIVE_SUFFIXES:
                    yield path


def default_native_roots():
    here = pathlib.Path(__file__).resolve()
    gradle_root = here.parents[2]          # PixelPlayerOSS
    workspace = here.parents[3]            # Polylute
    roots = []
    for libs in (workspace / "decent-player" / "libs", gradle_root / "app" / "src" / "main"):
        if libs.is_dir():
            roots += sorted(libs.glob("*/src/main/jni")) + sorted(libs.glob("*/src/main/cpp"))
            roots += [p for p in (libs / "jni", libs / "cpp") if p.is_dir()]
    return roots


# ---------------------------------------------------------------------------------------------------------------------------------------------
# the release: dex, and R8's mapping
# ---------------------------------------------------------------------------------------------------------------------------------------------

class DexIndex:
    """What the dex files of an artifact declare: classes (`a/b/C`), their superclass, methods `(cls, name, "(sig)ret")`, fields `(cls, name, "type")`."""

    def __init__(self):
        self.classes = {}
        self.methods = set()
        self.fields = set()
        self.method_names = collections.defaultdict(set)   # (name, sig) -> classes, for a lookup whose class cannot be told

    def add(self, data):
        header = struct.unpack_from("<8sI20sIIIIIIIIIIIIIIIIII", data, 0)
        (_, _, _, _, _, _, _, _, _, string_ids_size, string_ids_off, type_ids_size, type_ids_off, proto_ids_size, proto_ids_off,
         field_ids_size, field_ids_off, method_ids_size, method_ids_off, class_defs_size, class_defs_off) = header[:21]

        def uleb(offset):
            result = 0
            shift = 0
            while True:
                b = data[offset]
                offset += 1
                result |= (b & 0x7F) << shift
                if not b & 0x80:
                    return result, offset
                shift += 7

        strings = []
        for i in range(string_ids_size):
            off = struct.unpack_from("<I", data, string_ids_off + 4 * i)[0]
            _, start = uleb(off)
            end = data.index(b"\0", start)
            strings.append(data[start:end].decode("utf-8", errors="replace"))
        types = [strings[struct.unpack_from("<I", data, type_ids_off + 4 * i)[0]] for i in range(type_ids_size)]

        def internal(descriptor):
            return descriptor[1:-1] if descriptor.startswith("L") and descriptor.endswith(";") else descriptor

        protos = []
        for i in range(proto_ids_size):
            _, ret, params_off = struct.unpack_from("<III", data, proto_ids_off + 12 * i)
            params = []
            if params_off:
                size = struct.unpack_from("<I", data, params_off)[0]
                params = [types[t] for t in struct.unpack_from("<%dH" % size, data, params_off + 4)] if size else []
            protos.append("(" + "".join(params) + ")" + types[ret])
        for i in range(field_ids_size):
            cls, typ, name = struct.unpack_from("<HHI", data, field_ids_off + 8 * i)
            self.fields.add((internal(types[cls]), strings[name], types[typ]))
        for i in range(method_ids_size):
            cls, proto, name = struct.unpack_from("<HHI", data, method_ids_off + 8 * i)
            key = (internal(types[cls]), strings[name], protos[proto])
            self.methods.add(key)
            self.method_names[(key[1], key[2])].add(key[0])
        for i in range(class_defs_size):
            cls, _, superclass = struct.unpack_from("<III", data, class_defs_off + 32 * i)
            self.classes[internal(types[cls])] = internal(types[superclass]) if superclass != 0xFFFFFFFF else None

    def has_method(self, cls, name, sig):
        """The class declares the method, or a superclass does (a lookup on the class finds what it inherits)."""
        seen = set()
        while cls and cls not in seen:
            seen.add(cls)
            if (cls, name, sig) in self.methods:
                return True
            cls = self.classes.get(cls)
        return False

    def has_field(self, cls, name, sig):
        seen = set()
        while cls and cls not in seen:
            seen.add(cls)
            if (cls, name, sig) in self.fields:
                return True
            cls = self.classes.get(cls)
        return False


def read_dex_index(archive_path):
    index = DexIndex()
    count = 0
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            if re.fullmatch(r"(?:[^/]+/dex/|)classes\d*\.dex", info.filename):
                index.add(archive.read(info))
                count += 1
    return index, count


JAVA_TYPES = {"V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long", "F": "float", "D": "double"}


def java_type(descriptor):
    dims = len(descriptor) - len(descriptor.lstrip("["))
    base = descriptor[dims:]
    name = JAVA_TYPES[base] if base in JAVA_TYPES else base[1:-1].replace("/", ".")
    return name + "[]" * dims


def split_signature(sig):
    """`(IL/a/B;[J)V` -> ([`I`, `L/a/B;`, `[J`], `V`)."""
    m = re.fullmatch(r"\((.*)\)(.+)", sig)
    params = re.findall(r"\[*(?:[VZBCSIJFD]|L[^;]+;)", m.group(1))
    return params, m.group(2)


class Mapping:
    """The members R8 wrote down for the classes asked about: `renamed(cls, name, sig)` -> the new name, or None when it kept its own."""

    def __init__(self, path, wanted):
        self.classes = {}      # original internal name -> obfuscated internal name
        self.methods = {}      # (cls, "ret name(args)") -> obfuscated name
        self.fields = {}       # (cls, "type name") -> obfuscated name
        wanted_java = {c.replace("/", ".") for c in wanted}
        current = None
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if line.startswith("#"):
                    continue
                if not line.startswith(" "):
                    m = re.match(r"^(\S+) -> (\S+):$", line)
                    current = None
                    if m and m.group(1) in wanted_java:
                        current = m.group(1).replace(".", "/")
                        self.classes[current] = m.group(2).replace(".", "/")
                    continue
                if current is None or line.lstrip().startswith("#"):
                    continue
                mm = re.match(r"^\s+(?:\d+:\d+:)?(\S+) ([\w$<>]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$", line)
                if mm:
                    self.methods[(current, f"{mm.group(1)} {mm.group(2)}({mm.group(3)})")] = mm.group(4)
                    continue
                fm = re.match(r"^\s+(\S+) ([\w$]+) -> (\S+)$", line)
                if fm:
                    self.fields[(current, f"{fm.group(1)} {fm.group(2)}")] = fm.group(3)

    def class_renamed(self, cls):
        new = self.classes.get(cls)
        return new if new is not None and new != cls else None

    def method_renamed(self, cls, name, sig):
        params, ret = split_signature(sig)
        key = (cls, f"{java_type(ret)} {name}({','.join(java_type(p) for p in params)})")
        new = self.methods.get(key)
        return new if new is not None and new != name else None

    def method_renamed_any(self, cls, name):
        """The new name of a method called [name] in [cls] whatever its arguments (a native function is bound by name alone), or None."""
        for (owner, signature), new in self.methods.items():
            if owner == cls and re.search(r"[ .]%s\(" % re.escape(name), signature) and new != name:
                return new
        return None

    def field_renamed(self, cls, name, sig):
        new = self.fields.get((cls, f"{java_type(sig)} {name}"))
        return new if new is not None and new != name else None


# ---------------------------------------------------------------------------------------------------------------------------------------------
# the check
# ---------------------------------------------------------------------------------------------------------------------------------------------

def is_platform(cls):
    return cls.startswith(PLATFORM_PREFIXES)


def check(lookups, exports, dex, mapping):
    """[problem string] for everything native code names that the release does not have under that name."""
    problems = []

    def class_problem(cls, where):
        if is_platform(cls):
            return None
        if cls not in dex.classes:
            renamed = mapping.class_renamed(cls) if mapping else None
            hint = f" (R8 renamed it to {renamed})" if renamed else ""
            return f"{where}: class {cls} is not in the release under that name{hint}"
        return None

    for lookup in lookups:
        if lookup.kind == "class":
            p = class_problem(lookup.cls, lookup.where)
            if p:
                problems.append(p)
            continue
        cls = lookup.cls
        if cls is not None and is_platform(cls):
            continue
        what = "method" if lookup.kind == "method" else "field"
        if cls is not None:
            p = class_problem(cls, lookup.where)
            if p:
                problems.append(p)
                continue
            found = dex.has_method(cls, lookup.name, lookup.sig) if what == "method" else dex.has_field(cls, lookup.name, lookup.sig)
            renamed = None
            if mapping:
                renamed = (mapping.method_renamed if what == "method" else mapping.field_renamed)(cls, lookup.name, lookup.sig)
            if renamed:
                problems.append(f"{lookup.where}: {what} {cls}.{lookup.name}{lookup.sig} was renamed by R8 to {renamed}")
            elif not found:
                problems.append(f"{lookup.where}: {what} {cls}.{lookup.name}{lookup.sig} is not in the release")
        else:
            # the class is not known from the source: some class of the release has to declare that name and signature
            if what == "method" and (lookup.name, lookup.sig) not in dex.method_names:
                problems.append(f"{lookup.where}: no class in the release declares method {lookup.name}{lookup.sig}")
            if what == "field" and not any(f[1] == lookup.name and f[2] == lookup.sig for f in dex.fields):
                problems.append(f"{lookup.where}: no class in the release declares field {lookup.name} {lookup.sig}")

    # A function the library exports is bound to a `native` method by the class and method name. The JVM only asks for the binding of a
    # method the Java code calls, and R8 removes a `native` method nothing calls (the C++ may well define more functions than Java
    # declares), so a class or method that is simply absent is unused and is not a failure. What fails is a class or method R8 kept
    # under another name: the mapping says so, and the JVM would not find the function for it.
    for export in exports:
        if is_platform(export.cls):
            continue
        if export.cls not in dex.classes:
            renamed = mapping.class_renamed(export.cls) if mapping else None
            if renamed:
                problems.append(f"{export.where}: native methods are bound to class {export.cls}, which R8 renamed to {renamed}")
            continue
        if export.method is None or mapping is None:
            continue
        renamed = mapping.method_renamed_any(export.cls, export.method)
        if renamed:
            problems.append(f"{export.where}: native method {export.cls}.{export.method} was renamed by R8 to {renamed}")
    return sorted(set(problems))


def wanted_classes(lookups, exports):
    names = {l.cls for l in lookups if l.cls} | {e.cls for e in exports}
    return {n for n in names if not is_platform(n)}


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("artifacts", nargs="+", help="the AAB or APK that ships")
    parser.add_argument("--mapping", help="R8's mapping.txt for it")
    parser.add_argument("--native", action="append", help="a native source directory or file (repeatable); default: the libraries' and the app's jni/cpp folders")
    args = parser.parse_args(argv)

    roots = args.native or default_native_roots()
    lookups, exports = [], []
    files = 0
    for path in native_files(roots):
        files += 1
        l, e = scan_source(path)
        lookups += l
        exports += e
    if not lookups and not exports:
        print(f"nothing to check: no JNI lookup or export in {files} native files under {[str(r) for r in roots]}", file=sys.stderr)
        return 2
    wanted = wanted_classes(lookups, exports)
    mapping = None
    if args.mapping:
        mapping = Mapping(args.mapping, wanted)
    else:
        print("no mapping given: only the dex is read")

    failed = False
    for artifact in args.artifacts:
        dex, dex_files = read_dex_index(artifact)
        if dex_files == 0:
            print(f"nothing to check: no classes*.dex in {artifact}", file=sys.stderr)
            return 2
        problems = check(lookups, exports, dex, mapping)
        print(f"== {artifact}: {dex_files} dex files, {len(dex.classes)} classes; {len(lookups)} JNI lookups and {len(exports)} exported "
              f"native functions from {files} native files")
        for problem in problems:
            print("  FAIL", problem)
        if not problems:
            print("  ok   every class, method and field native code looks up is in the release under its own name")
        failed = failed or bool(problems)
    print("jni keeps:", "FAILED" if failed else "clean")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
