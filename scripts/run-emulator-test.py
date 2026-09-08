#!/usr/bin/env python3
"""Run against the plugin repository's isolated scripts/android-fixture.mjs Host."""
import json, os, pathlib, subprocess, urllib.parse, urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
os.environ.setdefault('ANDROID_SERIAL','emulator-5554')
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')))
adb=str(sdk/'platform-tools/adb')
fixture=json.load(open(os.environ.get('HARNESS_ANDROID_FIXTURE','/tmp/harness-android-fixture.json')))
req=urllib.request.Request(fixture['localOrigin']+'/api/plugin/mobile-remote/pair/invite',data=json.dumps({'hostEpoch':fixture['hostEpoch']}).encode(),headers={'Origin':fixture['localOrigin'],'Content-Type':'application/json'})
with urllib.request.urlopen(req) as response: invitation=json.load(response)
pairing=fixture['origin']+'/remote/pair#'+urllib.parse.urlencode({'v':'1','hostId':fixture['hostId'],'pin':fixture['pin'],'expires':invitation['expiresAt'],'invite':invitation['code']})
upload=pathlib.Path('/tmp/harness-native-picker.txt');upload.write_text('Android system picker UTF-8 测试\n')
download=pathlib.Path('/tmp/harness-native-download.bin');download.write_bytes(bytes(range(256))*2048)
subprocess.run([adb,'push',str(upload),'/sdcard/Download/harness-remote-qa.txt'],check=True)
subprocess.run([adb,'shell','rm','-f','/sdcard/Download/harness-native-download.bin'],check=True)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    subprocess.run([adb,'install','-r',str(root/apk)],check=True)
subprocess.run([adb,'shell','pm','clear','com.muzermat.harnessremote.debug'],check=True)
# ADB concatenates shell arguments; quote the URL as shell data, never print its invitation.
result=subprocess.run([adb,'shell','am','instrument','-w','-r','-e','pairing',"'"+pairing+"'",'com.muzermat.harnessremote.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
print(result.stdout)
if result.returncode or 'FAILURES' in result.stdout or 'Process crashed' in result.stdout or 'OK (1 test)' not in result.stdout:
    raise SystemExit(1)

received=subprocess.check_output([adb,'exec-out','cat','/sdcard/Download/harness-native-download.bin'])
assert received==download.read_bytes(), 'Saved native download differs from source'
print('System file picker and 512 KiB native download verified.')
