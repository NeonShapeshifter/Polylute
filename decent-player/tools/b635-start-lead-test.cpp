#define main original_gate_main
#include "natural-join-test.cpp"
#undef main
static void startLead(int rate, int lead, int stall) {
    Writer w(rate,true,rate==96000?44100:0);
#ifdef B635_PLATEAU_BEFORE
    w.ctx->plan.resumeLeadFrames=(int)usbFramesForMs(rate,lead);
#else
    w.ctx->plan.startLeadFrames=(int)usbFramesForMs(rate,lead);
#endif
    g_usb.stallAfterFirstUrbUs=stall*1000LL;
    const Pcm source=makeNoise(16*2048,24,0x6357);
    const int64_t expectedPrime=silenceToReach(usbFramesForMs(rate,lead),w.ctx->plan.silenceChunkFrames,0);
    for(int at=0;at<source.frames();at+=2048)w.write(source,at,2048);
    const auto wire=w.finish();sleepMs(400);
    size_t zeros=0;while(zeros<g_usb.capture.size() && g_usb.capture[zeros]==0)++zeros;
    CHECK(w.startSilenceFrames==expectedPrime && (int64_t)(zeros/8)==expectedPrime,"start lead doubled/missing pre-roll");
    CHECK(wire==toUsb32(source),"start lead changed healthy music bytes");
    CHECK(w.ctx->framesWritten==source.frames() && g_usb.playedFrames(nowUs())==source.frames()+w.startSilenceFrames,"start lead moved frame/position clock");
    printf("STARTLEAD rate=%d leadMs=%d primedMs=%.3f stallMs=%d gaps=%d maxDryMs=%.3f bytes=EXACT singlePrime=YES position=UNCHANGED\n",rate,lead,w.startSilenceFrames*1000.0/rate,stall,g_usb.gaps,g_usb.maxGapUs/1000.0);
    if(stall<lead-2) CHECK(g_usb.gaps==0,"stall below selected start lead ran dry");
    if(stall==175) CHECK(g_usb.gaps>0,"175ms incorrectly promised gap-free");
}
static void chosenDefault() {
    Writer w(96000,true,44100);g_usb.stallAfterFirstUrbUs=120000;
    const Pcm source=makeNoise(16*2048,24,0x6357128);
    for(int at=0;at<source.frames();at+=2048)w.write(source,at,2048);
    const auto wire=w.finish();
#ifdef B635_PLATEAU_BEFORE
    const int actual=w.ctx->plan.resumeLeadFrames;
#else
    const int actual=w.ctx->plan.startLeadFrames;
    CHECK(w.ctx->plan.resumeLeadFrames==4800,"ordinary user resume was changed");
#endif
    CHECK(actual==12288,"default start lead is still 50ms");
    CHECK(g_usb.gaps==0,"default start does not cover 120ms first-URB stall");
    CHECK(wire==toUsb32(source),"default start changed healthy bytes");
    printf("STARTLEAD default leadFrames=%d rate=96000 stallMs=120 gaps=%d bytes=EXACT\n",actual,g_usb.gaps);
}
#ifndef B635_PLATEAU_BEFORE
extern "C" void Java_com_decent_usbaudio_UsbAudioStream_nativeConfigureStartup(JNIEnv *, jobject, jlong, jstring, jint);
static void explicitPreroll() {
    Writer w(96000, true, 44100);
    const int prerollFrames = 24000; // configured 250ms before actual music
    Java_com_decent_usbaudio_UsbAudioStream_nativeConfigureStartup(nullptr, nullptr, w.usb, nullptr, prerollFrames);
    Pcm silence;
    silence.bits = 16;
    silence.samples.resize(prerollFrames * 2);
    for (int at = 0; at < prerollFrames; at += 8192) w.write(silence, at, std::min(8192, prerollFrames - at));
    const Pcm source = makeNoise(4096, 24, 0x6357250);
    w.write(source, 0, source.frames());
    w.finish();
    std::vector<uint8_t> expected(prerollFrames * 8, 0);
    appendUsb(expected, source, 0, source.frames());
    CHECK(w.startSilenceFrames == 0 && g_usb.capture == expected, "configured pre-roll got a second native prime");
    CHECK(w.ctx->framesWritten == prerollFrames + source.frames(), "configured pre-roll moved frame accounting");
    puts("STARTLEAD configured 250ms pre-roll exactly once, no added prime, music EXACT, frame accounting PASS");
}
#endif
int main() {
    chosenDefault();
#ifndef B635_PLATEAU_BEFORE
    explicitPreroll();
#endif
    for(int rate : {44100,96000,192000})for(int lead : {50,100,128})for(int stall : {40,60,82,100,120,175})startLead(rate,lead,stall);
    printf("STARTLEAD measurement %s failures=%d\n",g_failures?"FAIL":"PASS",g_failures);return g_failures?1:0;
}
