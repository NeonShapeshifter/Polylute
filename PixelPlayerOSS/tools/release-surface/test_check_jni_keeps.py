import os
import pathlib
import struct
import tempfile
import unittest
import zipfile

import check_jni_keeps as jni

# ---------------------------------------------------------------------------------------------------------------------------------------------
# a dex file small enough to write by hand: strings, types, protos, fields, methods and class definitions, nothing else
# ---------------------------------------------------------------------------------------------------------------------------------------------

def uleb(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)

class DexBuilder:
    """`cls("a/B", methods=[("name", "(I)V")], fields=[("f", "I")], superclass="java/lang/Object")` and then `build()`."""

    def __init__(self):
        self.defs = []

    def cls(self, name, methods=(), fields=(), superclass="java/lang/Object"):
        self.defs.append((name, superclass, list(methods), list(fields)))
        return self

    @staticmethod
    def descriptor(internal):
        return internal if internal[0] in "VZBCSIJFD[" and len(internal) <= 2 or internal.startswith("[") else "L" + internal + ";"

    def build(self):
        strings, types, protos, fields, methods = {}, {}, {}, [], []

        def string(s):
            return strings.setdefault(s, len(strings))

        def type_(desc):
            string(desc)
            return types.setdefault(desc, len(types))

        def proto(sig):
            params, ret = jni.split_signature(sig)
            for p in params:
                type_(p)
            type_(ret)
            string("S")
            return protos.setdefault(sig, len(protos))

        class_defs = []
        for name, superclass, ms, fs in self.defs:
            c = type_("L" + name + ";")
            s = type_("L" + superclass + ";") if superclass else None
            class_defs.append((c, s))
            for mname, sig in ms:
                methods.append((c, proto(sig), string(mname)))
            for fname, ftype in fs:
                fields.append((c, type_(ftype), string(fname)))

        string_list = sorted(strings, key=strings.get)
        type_list = sorted(types, key=types.get)
        proto_list = sorted(protos, key=protos.get)

        header_size = 0x70
        string_ids_off = header_size
        type_ids_off = string_ids_off + 4 * len(string_list)
        proto_ids_off = type_ids_off + 4 * len(type_list)
        field_ids_off = proto_ids_off + 12 * len(proto_list)
        method_ids_off = field_ids_off + 8 * len(fields)
        class_defs_off = method_ids_off + 8 * len(methods)
        data_off = class_defs_off + 32 * len(class_defs)

        data = bytearray()
        string_offsets = []
        for s in string_list:
            string_offsets.append(data_off + len(data))
            raw = s.encode("utf-8")
            data += uleb(len(s)) + raw + b"\0"
        while (data_off + len(data)) % 4:
            data += b"\0"
        type_list_offsets = {}
        for sig in proto_list:
            params, _ = jni.split_signature(sig)
            if params:
                type_list_offsets[sig] = data_off + len(data)
                data += struct.pack("<I", len(params)) + b"".join(struct.pack("<H", types[p]) for p in params)
                while (data_off + len(data)) % 4:
                    data += b"\0"

        body = bytearray()
        for off in string_offsets:
            body += struct.pack("<I", off)
        for t in type_list:
            body += struct.pack("<I", strings[t])
        for sig in proto_list:
            params, ret = jni.split_signature(sig)
            body += struct.pack("<III", strings["S"], types[ret], type_list_offsets.get(sig, 0))
        for c, t, n in fields:
            body += struct.pack("<HHI", c, t, n)
        for c, p, n in methods:
            body += struct.pack("<HHI", c, p, n)
        for c, s in class_defs:
            body += struct.pack("<IIIIIIII", c, 1, 0xFFFFFFFF if s is None else s, 0, 0xFFFFFFFF, 0, 0, 0)

        header = bytearray(header_size)
        header[0:8] = b"dex\n035\0"
        struct.pack_into(
            "<IIIIIIIIIIIIIIII", header, 0x24,
            header_size, 0x12345678, 0, 0, 0,        # header_size, endian_tag, link_size, link_off, map_off
            len(string_list), string_ids_off, len(type_list), type_ids_off, len(proto_list), proto_ids_off,
            len(fields), field_ids_off, len(methods), method_ids_off, len(class_defs),
        )
        struct.pack_into("<I", header, 0x64, class_defs_off)
        return bytes(header) + bytes(body) + bytes(data)

def archive(dex_files):
    handle, path = tempfile.mkstemp(suffix=".aab")
    os.close(handle)
    with zipfile.ZipFile(path, "w") as z:
        for i, data in enumerate(dex_files):
            z.writestr("base/dex/classes%s.dex" % ("" if i == 0 else i + 1), data)
    return path

FLAC = "androidx/media3/decoder/flac/FlacDecoderJni"
PICTURE = "androidx/media3/extractor/metadata/flac/PictureFrame"
METADATA = "androidx/media3/extractor/FlacStreamMetadata"
PICTURE_SIG = "(ILjava/lang/String;Ljava/lang/String;IIII[B)V"
METADATA_SIG = "(IIIIIIIJLjava/util/ArrayList;Ljava/util/ArrayList;)V"

def release(extra_methods=(), renamed_read=False, drop=()):
    """The classes native code looks for, as R8 leaves them when it keeps their names (or, with [renamed_read], when it does not)."""
    b = DexBuilder()
    flac = [("a" if renamed_read else "read", "(Ljava/nio/ByteBuffer;)I"), ("flacInit", "()J"), ("flacRelease", "(J)V")]
    if FLAC not in drop:
        b.cls(FLAC, methods=flac + list(extra_methods))
    if PICTURE not in drop:
        b.cls(PICTURE, methods=[("<init>", PICTURE_SIG)])
    if METADATA not in drop:
        b.cls(METADATA, methods=[("<init>", METADATA_SIG)])
    return b.build()

FLAC_SOURCE = r'''
#define DECODER_FUNC(RETURN_TYPE, NAME, ...)                              \
  extern "C" {                                                            \
  JNIEXPORT RETURN_TYPE                                                   \
  Java_androidx_media3_decoder_flac_FlacDecoderJni_##NAME(JNIEnv* env,    \
                                                          jobject thiz,   \
                                                          ##__VA_ARGS__); \
  }                                                                       \
  JNIEXPORT RETURN_TYPE                                                   \
  Java_androidx_media3_decoder_flac_FlacDecoderJni_##NAME(                \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

class JavaDataSource {
 public:
  void setFlacDecoderJni(JNIEnv* env, jobject flacDecoderJni) {
    if (mid == NULL) {
      jclass cls = env->GetObjectClass(flacDecoderJni);
      mid = env->GetMethodID(cls, "read", "(Ljava/nio/ByteBuffer;)I");   // the lookup that failed in 1.0.0
      env->DeleteLocalRef(cls);
    }
  }
};

DECODER_FUNC(jlong, flacInit) {
  return 0;
}

DECODER_FUNC(void, flacRelease, jlong jContext) {
}

DECODER_FUNC(jobject, flacDecodeMetadata, jlong jContext) {
  jclass arrayListClass = env->FindClass("java/util/ArrayList");
  jmethodID arrayListConstructor = env->GetMethodID(arrayListClass, "<init>", "()V");
  jclass pictureFrameClass =
      env->FindClass("androidx/media3/extractor/metadata/flac/PictureFrame");
  jmethodID pictureFrameConstructor =
      env->GetMethodID(pictureFrameClass, "<init>",
                       "(ILjava/lang/String;Ljava/lang/String;IIII[B)V");
  jclass flacStreamMetadataClass = env->FindClass(
      "androidx/media3/extractor/"
      "FlacStreamMetadata");
  jmethodID flacStreamMetadataConstructor =
      env->GetMethodID(flacStreamMetadataClass, "<init>",
                       "(IIIIIIIJLjava/util/ArrayList;Ljava/util/ArrayList;)V");
  return env->NewObject(flacStreamMetadataClass, flacStreamMetadataConstructor, 1);
}
'''

DRIVER_SOURCE = r'''
extern "C" JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetSecondSummaries(JNIEnv *env, jclass, jlong windowMs) {
    return nullptr;
}
JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudio_1Write(JNIEnv *, jobject, jlong h) {}
// Java_com_decent_usbaudio_Commented_nativeNothing is in a comment
'''

def source_file(text):
    handle, path = tempfile.mkstemp(suffix=".cc")
    os.close(handle)
    pathlib.Path(path).write_text(text, encoding="utf-8")
    return path

class ReadingNativeSources(unittest.TestCase):
    def setUp(self):
        self.paths = []

    def tearDown(self):
        for p in self.paths:
            os.unlink(p)

    def scan(self, text):
        path = source_file(text)
        self.paths.append(path)
        return jni.scan_source(path)

    def test_every_by_name_lookup_of_the_flac_decoder_is_found_with_its_class(self):
        lookups, _ = self.scan(FLAC_SOURCE)
        found = {(l.kind, l.cls, l.name, l.sig) for l in lookups}
        self.assertIn(("method", FLAC, "read", "(Ljava/nio/ByteBuffer;)I"), found)
        self.assertIn(("class", PICTURE, None, None), found)
        self.assertIn(("method", PICTURE, "<init>", PICTURE_SIG), found)
        # a name the source splits over two string literals is one name
        self.assertIn(("class", METADATA, None, None), found)
        self.assertIn(("method", METADATA, "<init>", METADATA_SIG), found)
        self.assertIn(("method", "java/util/ArrayList", "<init>", "()V"), found)

    def test_the_class_of_a_get_object_class_is_the_class_the_file_exports_for(self):
        lookups, _ = self.scan(FLAC_SOURCE)
        read = [l for l in lookups if l.name == "read"]
        self.assertEqual([l.cls for l in read], [FLAC])

    def test_a_macro_that_builds_the_exported_names_gives_the_methods_its_uses_name(self):
        _, exports = self.scan(FLAC_SOURCE)
        self.assertEqual(sorted(e.method for e in exports), ["flacDecodeMetadata", "flacInit", "flacRelease"])
        self.assertTrue(all(e.cls == FLAC for e in exports))

    def test_exported_functions_are_demangled_and_a_commented_one_is_not_one(self):
        _, exports = self.scan(DRIVER_SOURCE)
        names = sorted((e.cls, e.method) for e in exports)
        self.assertEqual(names, [
            ("com/decent/usbaudio/UsbAudioStream", "nativeGetSecondSummaries"),
            ("com/decent/usbaudio/UsbAudioStream", "nativeUsbAudio_Write"),
        ])

    def test_jni_name_mangling(self):
        self.assertEqual(jni.demangle("a_b_C_my_1name"), "a/b/C/my_name")
        self.assertEqual(jni.demangle("a_b_Outer_00024Inner_run"), "a/b/Outer$Inner/run")

    def test_a_lookup_with_a_computed_name_is_not_guessed_at(self):
        lookups, _ = self.scan('void f(JNIEnv* env, const char* n) { jclass c = env->FindClass(n); env->GetMethodID(c, n, "()V"); }')
        self.assertEqual(lookups, [])

    def test_the_real_native_sources_have_the_lookups_and_the_exports_the_check_exists_for(self):
        roots = jni.default_native_roots()
        if not roots:
            self.skipTest("the decent-player sources are not next to this tree")
        lookups, exports = [], []
        for path in jni.native_files(roots):
            l, e = jni.scan_source(path)
            lookups += l
            exports += e
        self.assertIn(("method", FLAC, "read", "(Ljava/nio/ByteBuffer;)I"), {(l.kind, l.cls, l.name, l.sig) for l in lookups})
        # B46-A3: the transport plan's facade adds 4 exports (UsbTransportPlanner: nativePlan, nativeFieldCount, nativeReasonName,
        # nativeDefaultQueueFrames) and the stream one (UsbAudioStream.nativeGetTransportPlan): 53 -> 58
        # B47-A5: the locked-up DAC's recovery adds 7 to UsbAudioStream (nativeDacLockState, nativeDacRecoveryBegin, nativeDacRecoveryEnd,
        # nativeNoteDacRecovery, nativeSetDacRecoveryEnabled, nativeRequestDacRecovery, nativeGetDacRecoveryStats): 58 -> 65
        # B48-fix1: the feedback start guard's switch and its reading (nativeSetFeedbackStartGuardEnabled, nativeFeedbackStartGuardEnabled): 65 -> 67
        # B48-fix2: nativeNoteDataAltSet records the actual successful data SETINTERFACE: 67 -> 68
        self.assertEqual(len(exports), 68)
        self.assertEqual(
            {owner: sum(e.cls == owner for e in exports) for owner in {e.cls for e in exports}},
            {"com/decent/usbaudio/UsbAudioStream": 50, "com/decent/usbaudio/UsbTransportPlanner": 4,
             "androidx/media3/decoder/flac/FlacDecoderJni": 14},
        )
        self.assertIn(("com/decent/usbaudio/UsbAudioStream", "nativeGetSecondSummaries"), {(e.cls, e.method) for e in exports})
        self.assertIn(("com/decent/usbaudio/UsbTransportPlanner", "nativePlan"), {(e.cls, e.method) for e in exports})

class ReadingADex(unittest.TestCase):
    def test_classes_methods_fields_and_superclasses_come_back_as_they_went_in(self):
        raw = (DexBuilder()
               .cls("a/Base", methods=[("hello", "()V")])
               .cls("a/Child", methods=[("run", "(I[Ljava/lang/String;)Z")], fields=[("count", "I")], superclass="a/Base")
               .build())
        index = jni.DexIndex()
        index.add(raw)
        self.assertEqual(set(index.classes), {"a/Base", "a/Child"})
        self.assertEqual(index.classes["a/Child"], "a/Base")
        self.assertIn(("a/Child", "run", "(I[Ljava/lang/String;)Z"), index.methods)
        self.assertIn(("a/Child", "count", "I"), index.fields)
        # a lookup on a class finds what the class inherits
        self.assertTrue(index.has_method("a/Child", "hello", "()V"))
        self.assertFalse(index.has_method("a/Base", "run", "(I[Ljava/lang/String;)Z"))

MAPPING_KEPT = """\
androidx.media3.decoder.flac.FlacDecoderJni -> androidx.media3.decoder.flac.FlacDecoderJni:
# {"id":"sourceFile","fileName":"FlacDecoderJni.java"}
    0:3:int read(java.nio.ByteBuffer):119:119 -> read
    1:2:void clearData():103:103 -> clearData
androidx.media3.extractor.metadata.flac.PictureFrame -> androidx.media3.extractor.metadata.flac.PictureFrame:
    0:3:void <init>(int,java.lang.String,java.lang.String,int,int,int,int,byte[]):10:10 -> <init>
androidx.media3.extractor.FlacStreamMetadata -> androidx.media3.extractor.FlacStreamMetadata:
    0:3:void <init>(int,int,int,int,int,int,int,long,java.util.ArrayList,java.util.ArrayList):10:10 -> <init>
"""

MAPPING_RENAMED_READ = MAPPING_KEPT.replace("119:119 -> read", "119:119 -> a")
MAPPING_RENAMED_CLASS = MAPPING_KEPT.replace(
    "androidx.media3.extractor.metadata.flac.PictureFrame -> androidx.media3.extractor.metadata.flac.PictureFrame:",
    "androidx.media3.extractor.metadata.flac.PictureFrame -> p1.a:",
)

class TheCheck(unittest.TestCase):
    def setUp(self):
        self.paths = []

    def tearDown(self):
        for p in self.paths:
            os.unlink(p)

    def sources(self, text):
        path = source_file(text)
        self.paths.append(path)
        return jni.scan_source(path)

    def mapping(self, text, lookups, exports):
        handle, path = tempfile.mkstemp(suffix=".txt")
        os.close(handle)
        pathlib.Path(path).write_text(text, encoding="utf-8")
        self.paths.append(path)
        return jni.Mapping(path, jni.wanted_classes(lookups, exports))

    def index(self, *dex_files):
        idx = jni.DexIndex()
        for raw in dex_files:
            idx.add(raw)
        return idx

    def run_check(self, dex, mapping_text=None):
        lookups, exports = self.sources(FLAC_SOURCE)
        mapping = self.mapping(mapping_text, lookups, exports) if mapping_text is not None else None
        return jni.check(lookups, exports, self.index(dex), mapping)

    def test_a_release_that_kept_every_name_is_clean_with_and_without_the_mapping(self):
        self.assertEqual(self.run_check(release()), [])
        self.assertEqual(self.run_check(release(), MAPPING_KEPT), [])

    def test_1_0_0_the_method_native_code_reads_by_name_renamed_by_r8_fails(self):
        problems = self.run_check(release(renamed_read=True), MAPPING_RENAMED_READ)
        self.assertTrue(any("FlacDecoderJni.read(Ljava/nio/ByteBuffer;)I" in p and "renamed by R8 to a" in p for p in problems), problems)

    def test_the_same_failure_is_seen_from_the_dex_alone(self):
        problems = self.run_check(release(renamed_read=True))
        self.assertTrue(any("FlacDecoderJni.read(Ljava/nio/ByteBuffer;)I is not in the release" in p for p in problems), problems)

    def test_a_class_native_code_finds_that_r8_renamed_or_removed_fails(self):
        problems = self.run_check(release(drop=(PICTURE,)), MAPPING_RENAMED_CLASS)
        self.assertTrue(any("class " + PICTURE in p and "renamed it to p1/a" in p for p in problems), problems)
        problems = self.run_check(release(drop=(METADATA,)))
        self.assertTrue(any("class " + METADATA + " is not in the release" in p for p in problems), problems)

    def test_a_constructor_whose_signature_changed_fails(self):
        b = (DexBuilder()
             .cls(FLAC, methods=[("read", "(Ljava/nio/ByteBuffer;)I"), ("flacInit", "()J"), ("flacRelease", "(J)V")])
             .cls(PICTURE, methods=[("<init>", "(I)V")])
             .cls(METADATA, methods=[("<init>", METADATA_SIG)]))
        problems = self.run_check(b.build())
        self.assertTrue(any(PICTURE + ".<init>" in p for p in problems), problems)

    def test_platform_classes_are_not_looked_for(self):
        # java/util/ArrayList is in no dex and cannot be renamed
        self.assertEqual(self.run_check(release()), [])

    def test_an_exported_native_method_that_r8_renamed_fails_and_one_nobody_calls_does_not(self):
        lookups, exports = self.sources(FLAC_SOURCE)
        mapping_text = MAPPING_KEPT.replace("    1:2:void clearData():103:103 -> clearData\n",
                                            "    int flacInit() -> b\n")
        problems = jni.check(lookups, exports, self.index(release()), self.mapping(mapping_text, lookups, exports))
        self.assertTrue(any("native method " + FLAC + ".flacInit was renamed by R8 to b" in p for p in problems), problems)
        # flacDecodeMetadata is in the C++ and not in the release at all: R8 took out a native method nothing called
        self.assertEqual(self.run_check(release(), MAPPING_KEPT), [])

    def test_an_export_whose_class_r8_renamed_fails(self):
        lookups, exports = self.sources(FLAC_SOURCE)
        text = MAPPING_KEPT.replace("androidx.media3.decoder.flac.FlacDecoderJni -> androidx.media3.decoder.flac.FlacDecoderJni:",
                                    "androidx.media3.decoder.flac.FlacDecoderJni -> q.b:")
        problems = jni.check(lookups, exports, self.index(release(drop=(FLAC,))), self.mapping(text, lookups, exports))
        self.assertTrue(any("native methods are bound to class " + FLAC + ", which R8 renamed to q/b" in p for p in problems), problems)

    def test_two_dex_files_are_one_release(self):
        first = DexBuilder().cls(FLAC, methods=[("read", "(Ljava/nio/ByteBuffer;)I"), ("flacInit", "()J"), ("flacRelease", "(J)V")]).build()
        second = (DexBuilder().cls(PICTURE, methods=[("<init>", PICTURE_SIG)]).cls(METADATA, methods=[("<init>", METADATA_SIG)])).build()
        lookups, exports = self.sources(FLAC_SOURCE)
        self.assertEqual(jni.check(lookups, exports, self.index(first, second), None), [])

class TheCommandLine(unittest.TestCase):
    def setUp(self):
        self.paths = []

    def tearDown(self):
        for p in self.paths:
            os.unlink(p)

    def run_main(self, dex_files, source_text=FLAC_SOURCE, mapping_text=None):
        artifact = archive(dex_files)
        source = source_file(source_text)
        self.paths += [artifact, source]
        argv = [artifact, "--native", source]
        if mapping_text is not None:
            handle, mapping = tempfile.mkstemp(suffix=".txt")
            os.close(handle)
            pathlib.Path(mapping).write_text(mapping_text, encoding="utf-8")
            self.paths.append(mapping)
            argv += ["--mapping", mapping]
        return jni.main(argv)

    def test_clean_is_zero(self):
        self.assertEqual(self.run_main([release()], mapping_text=MAPPING_KEPT), 0)

    def test_a_missing_name_is_one(self):
        self.assertEqual(self.run_main([release(renamed_read=True)], mapping_text=MAPPING_RENAMED_READ), 1)

    def test_no_dex_is_two(self):
        self.assertEqual(self.run_main([]), 2)

    def test_no_jni_in_the_sources_is_two(self):
        self.assertEqual(self.run_main([release()], source_text="int main() { return 0; }"), 2)

if __name__ == "__main__":
    unittest.main()
