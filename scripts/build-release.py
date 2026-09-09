#!/usr/bin/env python3
"""Build a signed, source-attributed APK. Keeps signing material outside repositories."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess
p=argparse.ArgumentParser();p.add_argument('--plugin',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('--signing-directory',type=pathlib.Path,default=pathlib.Path.home()/'.dsh/android-release');args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
def command(*cmd,cwd=root):return subprocess.check_output(cmd,cwd=cwd,text=True).strip()
for repo in [root,args.plugin]:
    if command('git','status','--porcelain',cwd=repo):raise SystemExit(f'Source must be committed before release: {repo.name}')
android_commit=command('git','rev-parse','HEAD');plugin_commit=command('git','rev-parse','HEAD',cwd=args.plugin)
gradle=(root/'app/build.gradle.kts').read_text();version=re.search(r'versionName = "([^"]+)"',gradle)[1];code=int(re.search(r'versionCode = (\d+)',gradle)[1])
env=os.environ.copy();env.update(HARNESS_SIGNING_STORE=str(args.signing_directory/'harness-remote.jks'),HARNESS_SIGNING_PASSWORD=(args.signing_directory/'signing-password').read_text())
subprocess.run(['./gradlew',':app:assembleRelease',':app:testReleaseUnitTest',':app:lintRelease'],cwd=root,env=env,check=True)
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')))
signer=sdk/'build-tools/35.0.0/apksigner'
apk=root/'app/build/outputs/apk/release/app-release.apk'
verification=command(str(signer),'verify','--verbose','--print-certs',str(apk))
certificate=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})',verification)[1]
args.output.mkdir(parents=True,exist_ok=True);name=f'harness-remote-{version}-android.apk';dest=args.output/name;shutil.copyfile(apk,dest)
def sha(path):return hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
manifest={'appId':'com.muzermat.harnessremote','version':version,'versionCode':code,'minimumAndroid':10,'minimumSdk':29,'targetSdk':36,'protocolVersion':1,'appTransportVersion':1,'compatiblePlugin':'>=0.2.0 <0.3.0','recommendedPlugin':'0.2.1','androidSource':{'repository':'https://github.com/y38501148-max/harness-remote-android','commit':android_commit},'pluginSource':{'repository':'https://github.com/y38501148-max/dsh-mobile-remote','commit':plugin_commit},'apk':{'filename':name,'sha256':sha(dest),'signingCertificateSha256':certificate},'network':'direct-ipv6-pinned-tls','distribution':'preview'}
m=args.output/'release-manifest.json';m.write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
(args.output/'SHA256SUMS').write_text(''.join(f'{sha(path)}  {path.name}\n' for path in [dest,m]))
print(json.dumps({'output':str(args.output),'apkSha256':sha(dest),'signingCertificateSha256':certificate,'androidCommit':android_commit,'pluginCommit':plugin_commit},indent=2))
