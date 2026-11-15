#!/usr/bin/env python3
"""Actual emulator navigation/capture helpers. Does not generate mocked screens."""
from pathlib import Path
import subprocess,time,xml.etree.ElementTree as E,re,shlex,urllib.parse,sys
ROOT=Path(__file__).resolve().parents[1];ADB=str(ROOT/'.tools/android-sdk/platform-tools/adb');SERIAL='emulator-5554';OUT=ROOT/'artifacts/redesign';OUT.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,check=True).stdout
def launch(uri):
 adb('shell','am force-stop com.velagram.tv')
 adb('shell','am start -W -a android.intent.action.VIEW -d '+shlex.quote(uri)+' com.velagram.tv')
 time.sleep(2)
def xml():
 adb('shell','uiautomator dump /sdcard/velagram-redesign.xml')
 return E.fromstring(adb('shell','cat /sdcard/velagram-redesign.xml'))
def click(text=None,desc=None,partial=False):
 root=xml();parents={child:parent for parent in root.iter() for child in parent}
 for n in root.iter('node'):
  value=n.get('content-desc','') if desc is not None else n.get('text','');target=desc if desc is not None else text
  if (target in value if partial else value==target):
   while n.get('clickable')!='true' and n in parents:n=parents[n]
   if n.get('clickable')!='true':continue
   x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell',f'input tap {(x1+x2)//2} {(y1+y2)//2}');time.sleep(.8);return
 raise RuntimeError('UI element not found: '+str((text,desc)))
def capture(name):
 (OUT/name).write_bytes(adb('exec-out','screencap','-p'));print(name,flush=True)
def back():adb('shell','input keyevent 4');time.sleep(.6)
if __name__=='__main__':
 if sys.argv[1]=='title':launch('velagram://title?'+urllib.parse.urlencode({'id':sys.argv[2],'kind':sys.argv[3]}));capture(sys.argv[4])
 elif sys.argv[1]=='capture':capture(sys.argv[2])
