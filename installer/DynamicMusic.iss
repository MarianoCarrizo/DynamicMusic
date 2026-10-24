; Inno Setup 6 script for DynamicMusic. Wraps a jpackage app-image plus a bundled ffmpeg.
;
; Compile (all three defines are required except OutputDir):
;   ISCC.exe installer\DynamicMusic.iss /DMyAppVersion=1.2.3 ^
;       /DAppImageDir=C:\path\to\app-image\DynamicMusic /DFfmpegDir=C:\path\to\ffmpeg-bundle ^
;       /DOutputDir=C:\path\to\output
;
; User data lives in %APPDATA%\DynamicMusic and is never touched by install or uninstall.

#ifndef MyAppVersion
  #error Pass the version: /DMyAppVersion=x.y.z
#endif
#ifndef AppImageDir
  #error Pass the jpackage app-image directory: /DAppImageDir=...
#endif
#ifndef FfmpegDir
  #error Pass the ffmpeg bundle directory: /DFfmpegDir=...
#endif
#ifndef OutputDir
  #define OutputDir "Output"
#endif

#define MyAppName "DynamicMusic"
#define MyAppExe "DynamicMusic.exe"

[Setup]
; Stable identity: keeping this GUID is what makes a newer installer upgrade in place. Never change it.
AppId={{79F75685-7F3D-42C7-AE06-D18F84A1AC47}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher=marianocarrizo
AppPublisherURL=https://github.com/MarianoCarrizo/DynamicMusic
AppSupportURL=https://github.com/MarianoCarrizo/DynamicMusic/issues
VersionInfoVersion={#MyAppVersion}

; Per-user install by default so silent updates never need admin; the wizard offers an all-users install.
DefaultDirName={autopf}\{#MyAppName}
DisableProgramGroupPage=yes
; Strictly per-user: silent self-updates must find and replace the same install without UAC
PrivilegesRequired=lowest

ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

; The in-app updater runs this installer with /CLOSEAPPLICATIONS while the app is exiting.
; The [Run] entry below relaunches the app, so Inno must not also restart it.
CloseApplications=yes
RestartApplications=no

SetupIconFile=..\icon.ico
UninstallDisplayIcon={app}\{#MyAppExe}
OutputDir={#OutputDir}
OutputBaseFilename=DynamicMusic-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Drop the previous version's files so renamed or removed jars and runtime files do not pile up.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\ffmpeg"

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#FfmpegDir}\*"; DestDir: "{app}\ffmpeg"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon

[Run]
; Interactive install: the usual "Launch" checkbox on the last page.
Filename: "{app}\{#MyAppExe}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
; Silent install (in-app update): relaunch the app automatically.
Filename: "{app}\{#MyAppExe}"; Flags: nowait runasoriginaluser; Check: WizardSilent
