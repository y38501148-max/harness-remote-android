#!/usr/bin/env python3
"""Validate the signed release through native UI, then retain pairing across reinstall."""
import json,os,pathlib,subprocess,urllib.request,urllib.parse
root=pathlib.Path(__file__).resolve().parents[1];os.environ.setdefault('ANDROID_SERIAL','emulator-5554')
adb=str(pathlib.Path(os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')))/'platform-tools/adb')
fixture=json.load(open(os.environ.get('HARNESS_ANDROID_FIXTURE','/tmp/harness-android-fixture.json')))
def devices():
    with urllib.request.urlopen(fixture['localOrigin']+'/api/plugin/mobile-remote/devices') as r:return {d['deviceId']:d for d in json.load(r)['devices']}
before=devices()
req=urllib.request.Request(fixture['localOrigin']+'/api/plugin/mobile-remote/pair/invite',data=json.dumps({'hostEpoch':fixture['hostEpoch']}).encode(),headers={'Origin':fixture['localOrigin'],'Content-Type':'application/json'})
with urllib.request.urlopen(req) as r:invite=json.load(r)
pairing=fixture['origin']+'/remote/pair#'+urllib.parse.urlencode({'v':'1','hostId':fixture['hostId'],'pin':fixture['pin'],'expires':invite['expiresAt'],'invite':invite['code']})
apk=str(root/'app/build/outputs/apk/release/app-release.apk')
for path in [apk,str(root/'app/build/outputs/apk/debug/app-debug.apk'),str(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:subprocess.run([adb,'install','-r',path],check=True)
subprocess.run([adb,'shell','pm','clear','com.muzermat.harnessremote'],check=True)
def test(method):
    r=subprocess.run([adb,'shell','am','instrument','-w','-r','-e','class','com.muzermat.harnessremote.SignedReleaseTest#'+method,'-e','pairing',"'"+pairing+"'",'com.muzermat.harnessremote.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
    print(r.stdout)
    if r.returncode or 'OK (1 test)' not in r.stdout or 'FAILURES' in r.stdout or 'Process crashed' in r.stdout:raise SystemExit(1)
test('pairsUsingNativePasteUi')
after=devices();added=after.keys()-before.keys();assert len(added)==1 and after[next(iter(added))]['state']=='approved'
subprocess.run([adb,'install','-r',apk],check=True);subprocess.run([adb,'shell','am','force-stop','com.muzermat.harnessremote'],check=True)
test('restoresAfterCoverInstall')
assert devices().keys()==after.keys(), 'Reinstall repeated pairing'
print('PASS: signed APK native pairing, same-signature cover install, process restart, no duplicate pairing.')
