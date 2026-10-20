#include "usb-audio-output.h"

#include <pthread.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr uint64_t BLOCK_BYTES = 1024 * 1024;

struct Sha256 {
    uint32_t state[8];
    uint64_t bitLength;
    uint8_t buffer[64];
    size_t bufferLength;
};

constexpr uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

void shaTransform(Sha256 *s, const uint8_t block[64]) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (uint32_t(block[i*4]) << 24) | (uint32_t(block[i*4+1]) << 16) |
               (uint32_t(block[i*4+2]) << 8) | uint32_t(block[i*4+3]);
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(w[i-15],7) ^ rotr(w[i-15],18) ^ (w[i-15] >> 3);
        uint32_t s1 = rotr(w[i-2],17) ^ rotr(w[i-2],19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    uint32_t a=s->state[0], b=s->state[1], c=s->state[2], d=s->state[3];
    uint32_t e=s->state[4], f=s->state[5], g=s->state[6], h=s->state[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t s1 = rotr(e,6) ^ rotr(e,11) ^ rotr(e,25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + s1 + ch + K[i] + w[i];
        uint32_t s0 = rotr(a,2) ^ rotr(a,13) ^ rotr(a,22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    s->state[0]+=a; s->state[1]+=b; s->state[2]+=c; s->state[3]+=d;
    s->state[4]+=e; s->state[5]+=f; s->state[6]+=g; s->state[7]+=h;
}

void shaInit(Sha256 *s) {
    const uint32_t initial[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
        0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    memcpy(s->state, initial, sizeof(initial));
    s->bitLength = 0;
    s->bufferLength = 0;
}

void shaUpdate(Sha256 *s, const uint8_t *data, size_t length) {
    while (length > 0) {
        size_t room = 64 - s->bufferLength;
        size_t take = length < room ? length : room;
        memcpy(s->buffer + s->bufferLength, data, take);
        s->bufferLength += take;
        data += take;
        length -= take;
        if (s->bufferLength == 64) {
            shaTransform(s, s->buffer);
            s->bitLength += 512;
            s->bufferLength = 0;
        }
    }
}

void shaFinal(Sha256 source, uint8_t digest[32]) {
    source.bitLength += source.bufferLength * 8;
    source.buffer[source.bufferLength++] = 0x80;
    if (source.bufferLength > 56) {
        while (source.bufferLength < 64) source.buffer[source.bufferLength++] = 0;
        shaTransform(&source, source.buffer);
        source.bufferLength = 0;
    }
    while (source.bufferLength < 56) source.buffer[source.bufferLength++] = 0;
    for (int i = 7; i >= 0; --i) source.buffer[source.bufferLength++] = uint8_t(source.bitLength >> (i*8));
    shaTransform(&source, source.buffer);
    for (int i = 0; i < 8; ++i) {
        digest[i*4] = uint8_t(source.state[i] >> 24);
        digest[i*4+1] = uint8_t(source.state[i] >> 16);
        digest[i*4+2] = uint8_t(source.state[i] >> 8);
        digest[i*4+3] = uint8_t(source.state[i]);
    }
}

std::string hexDigest(const Sha256 &sha) {
    uint8_t digest[32];
    shaFinal(sha, digest);
    static const char HEX[] = "0123456789abcdef";
    std::string result(64, '0');
    for (int i = 0; i < 32; ++i) {
        result[i*2] = HEX[digest[i] >> 4];
        result[i*2+1] = HEX[digest[i] & 15];
    }
    return result;
}

std::string jsonEscape(const std::string &value) {
    std::string out;
    for (char c : value) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c == '\n') out += "\\n";
        else if (static_cast<unsigned char>(c) >= 0x20) out += c;
    }
    return out;
}

struct ValidationState {
    bool active = false;
    std::string path;
    std::string label;
    Sha256 full;
    Sha256 block;
    uint64_t bytes = 0;
    uint64_t blockBytes = 0;
    uint64_t urbs = 0;
    uint64_t packets = 0;
    uint64_t expectedBytes = 0;
    int sampleRate = 0;
    int channels = 0;
    int slotBits = 0;
    int endpoint = 0;
    bool formatChanged = false;
    std::vector<std::string> blockHashes;
};

pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
std::atomic<bool> enabled(false);
ValidationState state;
std::string lastResult;
std::string lastStatus = "inactive";

void feedBlocks(const uint8_t *data, size_t length) {
    while (length > 0) {
        size_t room = static_cast<size_t>(BLOCK_BYTES - state.blockBytes);
        size_t take = length < room ? length : room;
        shaUpdate(&state.block, data, take);
        state.blockBytes += take;
        data += take;
        length -= take;
        if (state.blockBytes == BLOCK_BYTES) {
            state.blockHashes.push_back(hexDigest(state.block));
            shaInit(&state.block);
            state.blockBytes = 0;
        }
    }
}

} // namespace

bool startUsbPayloadValidation(const char *manifestPath, const char *label, uint64_t expectedBytes) {
    if (!manifestPath || manifestPath[0] == 0) return false;
    pthread_mutex_lock(&mutex);
    if (state.active) {
        pthread_mutex_unlock(&mutex);
        return false;
    }
    state = ValidationState();
    state.active = true;
    state.path = manifestPath;
    state.label = label ? label : "unnamed";
    state.expectedBytes = expectedBytes;
    shaInit(&state.full);
    shaInit(&state.block);
    lastStatus = "armed";
    enabled.store(true, std::memory_order_release);
    pthread_mutex_unlock(&mutex);
    return true;
}

void observeSubmittedUsbPayload(const UsbAudioContext *ctx, const uint8_t *payload,
                                int payloadBytes, int packetCount) {
    if (!enabled.load(std::memory_order_acquire) || !ctx || !payload || payloadBytes <= 0) return;
    pthread_mutex_lock(&mutex);
    if (state.active) {
        if (state.bytes == 0) {
            state.sampleRate = ctx->sampleRate;
            state.channels = ctx->channelCount;
            state.slotBits = ctx->bitDepth;
            state.endpoint = ctx->endpointOut;
            lastStatus = "capturing";
        } else if (state.sampleRate != ctx->sampleRate || state.channels != ctx->channelCount ||
                   state.slotBits != ctx->bitDepth || state.endpoint != ctx->endpointOut) {
            state.formatChanged = true;
        }
        size_t capturedBytes = static_cast<size_t>(payloadBytes);
        if (state.expectedBytes > 0) {
            const uint64_t remaining = state.expectedBytes - state.bytes;
            if (capturedBytes > remaining) capturedBytes = static_cast<size_t>(remaining);
        }
        shaUpdate(&state.full, payload, capturedBytes);
        feedBlocks(payload, capturedBytes);
        state.bytes += static_cast<uint64_t>(capturedBytes);
        state.urbs++;
        state.packets += static_cast<uint64_t>(packetCount);
        if (state.expectedBytes > 0 && state.bytes == state.expectedBytes) {
            lastStatus = "target_reached";
            enabled.store(false, std::memory_order_release);
        }
    }
    pthread_mutex_unlock(&mutex);
}

const char *stopUsbPayloadValidation() {
    enabled.store(false, std::memory_order_release);
    pthread_mutex_lock(&mutex);
    if (!state.active) {
        lastResult.clear();
        lastStatus = "inactive";
        pthread_mutex_unlock(&mutex);
        return nullptr;
    }
    if (state.blockBytes > 0) state.blockHashes.push_back(hexDigest(state.block));
    const uint64_t frameBytes = static_cast<uint64_t>((state.slotBits / 8) * state.channels);
    const uint64_t frames = frameBytes > 0 ? state.bytes / frameBytes : 0;
    FILE *file = fopen(state.path.c_str(), "wb");
    if (file) {
        fprintf(file, "{\n  \"schema\": 1,\n");
        fprintf(file, "  \"label\": \"%s\",\n", jsonEscape(state.label).c_str());
        fprintf(file, "  \"boundary\": \"successful USBDEVFS_SUBMITURB AudioStreaming payload\",\n");
        fprintf(file, "  \"sha256\": \"%s\",\n", hexDigest(state.full).c_str());
        fprintf(file, "  \"bytes\": %llu,\n  \"expected_bytes\": %llu,\n  \"target_reached\": %s,\n  \"frames\": %llu,\n",
                (unsigned long long)state.bytes,
                (unsigned long long)state.expectedBytes,
                state.expectedBytes > 0 && state.bytes == state.expectedBytes ? "true" : "false",
                (unsigned long long)frames);
        fprintf(file, "  \"sample_rate_hz\": %d,\n  \"channels\": %d,\n  \"slot_bits\": %d,\n",
                state.sampleRate, state.channels, state.slotBits);
        fprintf(file, "  \"endpoint_address\": %d,\n  \"submitted_urbs\": %llu,\n  \"iso_packets\": %llu,\n",
                state.endpoint, (unsigned long long)state.urbs, (unsigned long long)state.packets);
        fprintf(file, "  \"format_changed\": %s,\n  \"block_bytes\": %llu,\n  \"block_sha256\": [",
                state.formatChanged ? "true" : "false", (unsigned long long)BLOCK_BYTES);
        for (size_t i = 0; i < state.blockHashes.size(); ++i) {
            fprintf(file, "%s\"%s\"", i ? ", " : "", state.blockHashes[i].c_str());
        }
        fprintf(file, "]\n}\n");
        fclose(file);
        lastResult = state.path;
        lastStatus = "complete";
    } else {
        lastResult.clear();
        lastStatus = "write_failed";
    }
    state.active = false;
    pthread_mutex_unlock(&mutex);
    return lastResult.empty() ? nullptr : lastResult.c_str();
}

const char *getUsbPayloadValidationStatus() {
    pthread_mutex_lock(&mutex);
    static std::string snapshot;
    snapshot = lastStatus;
    if (state.active) {
        snapshot += ":bytes=" + std::to_string(state.bytes);
        snapshot += ":urbs=" + std::to_string(state.urbs);
    } else if (!lastResult.empty()) {
        snapshot += ":manifest=" + lastResult;
    }
    pthread_mutex_unlock(&mutex);
    return snapshot.c_str();
}
