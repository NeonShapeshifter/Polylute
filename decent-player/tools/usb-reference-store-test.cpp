#include "usb-journal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
static int failures;
#define CHECK(x, why) do { if (!(x)) { printf("FAIL %s\n", why); ++failures; } } while (0)
static const char *keyA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
static const char *keyB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
static bool learn(const char *key, uint32_t raw, double nominal, uint64_t session, UsbFeedbackReference *out) {
    // The producer deliberately tries a memory lock. A later reading retries if the
    // background consumer was copying the table; this fixture models that retry.
    for (int i=0;i<2000;++i) { if (usbJournalReferenceLearn(key,raw,nominal,session,out)) return true; usleep(1000); }
    return false;
}
int main() {
    char directory[]="/tmp/polylute-reference-store-XXXXXX";CHECK(mkdtemp(directory),"directory");
    CHECK(usbJournalConfigure(directory),"configure");UsbFeedbackReference r;
    CHECK(!usbJournalReferenceGet(keyA,&r),"new store trusted first reading");
    CHECK(learn(keyA,0xc0000,12,1,&r) && r.sessions==1,"first qualified session");
    CHECK(learn(keyA,0xc0140,12,1,&r) && r.sessions==1 && r.raw==0xc0000,"same session counted twice");
    CHECK(learn(keyA,0xc0000,12,2,&r) && r.sessions==2,"second independent agreeing session");
    CHECK(!usbJournalReferenceGet(keyB,&r),"rate or format history leaked");
    CHECK(!usbJournalReferenceLearn("serial=/private/song.flac",0xc0000,12,3,&r),"personal data accepted as key");
    CHECK(usbJournalFlush(5000),"initial drain");usbJournalShutdown();
    const pid_t child=fork();
    if(child==0) {
        UsbFeedbackReference saved;
        bool ok=usbJournalConfigure(directory) && usbJournalReferenceGet(keyA,&saved) && saved.sessions==2 && saved.raw==0xc0000;
        _exit(ok?0:9);
    }
    int status=0;waitpid(child,&status,0);CHECK(WIFEXITED(status)&&WEXITSTATUS(status)==0,"new process lost reference");
    CHECK(usbJournalConfigure(directory),"restart");usbJournalReferenceDegrade(keyA);
    CHECK(usbJournalReferenceGet(keyA,&r)&&r.sessions==0,"confidence not degraded");
    CHECK(learn(keyA,0xc0140,12,3,&r)&&r.sessions==1,"changed DAC first new clean session");
    CHECK(learn(keyA,0xc0140,12,4,&r)&&r.sessions==2,"changed DAC confidence did not heal");
    CHECK(learn(keyB,0x60000,6,5,&r)&&r.raw==0x60000&&r.sessions==1,"independent rate reference");
    for(int i=0;i<80;++i) {
        char key[65];snprintf(key,sizeof(key),"%064x",i);
        CHECK(learn(key,0xc0000,12,100+i,&r),"bounded store insert");
    }
    CHECK(usbJournalFlush(5000),"bounded drain");usbJournalShutdown();
    char name[1024];snprintf(name,sizeof(name),"%s/feedback-reference.txt",directory);struct stat st={};
    CHECK(stat(name,&st)==0 && st.st_size<=8192,"reference file beyond 8KiB");
    printf("REFERENCE store capacity=%d bytes=%lld fixedRecords=%zu process-restart/two-sessions/healing/format-isolation PASS\n",USB_FEEDBACK_REFERENCE_CAPACITY,(long long)st.st_size,sizeof(UsbFeedbackReference)*USB_FEEDBACK_REFERENCE_CAPACITY);
    FILE *file=fopen(name,"wb");fputs("B635REF1 badc0de\npartial-record",file);fclose(file);
    CHECK(usbJournalConfigure(directory),"corrupt restart");
    char key[65];snprintf(key,sizeof(key),"%064x",79);
    CHECK(!usbJournalReferenceGet(key,&r),"corrupt/truncated store gave trusted reference");
    usbJournalShutdown();
    const char *suffixes[]={"current.jsonl","previous.jsonl","feedback-reference.txt","feedback-reference.tmp"};
    for(const char *suffix : suffixes) {snprintf(name,sizeof(name),"%s/%s",directory,suffix);unlink(name);}rmdir(directory);
    printf("REFERENCE store %s failures=%d\n",failures?"FAIL":"PASS",failures);return failures?1:0;
}
