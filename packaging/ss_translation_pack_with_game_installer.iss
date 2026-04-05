; 以下变量由 make_exe.py 通过 /D 参数传入
; 如需手动编译，可直接在此修改对应值

; BRANCH_VARIANT_<分支名> -> TranslationPackVarient，例：(黑体版)
#ifndef TranslationPackVarient
  #define TranslationPackVarient ""
#endif
; APP_VERSION -> MyAppVersion，例：1.0.0
#ifndef MyAppVersion
  #define MyAppVersion ""
#endif
; GAME_VERSION -> GameVersion，例：0.98a-RC8
#ifndef GameVersion
  #define GameVersion ""
#endif
; ORIGINAL_GAME_FOLDER -> OriginalGameFolder，例：F:/Game/Starsector/098-RC8
#ifndef OriginalGameFolder
  #define OriginalGameFolder ""
#endif

#define GameBaseName "Starsector(远行星号)"
#define TranslationPackName " " + GameVersion + " 中文汉化版"
#define MyAppName GameBaseName + TranslationPackName + TranslationPackVarient
#define ShortcutName "远行星号 " + GameVersion
#define MyAppExeName "starsector.exe"
#define MyAppPublisher "远星汉化组"
#define MyAppURL "https://www.fossic.org/"
#define TranslationProjectFolder ".."
#define TargetInstallFolder "starsector-core"
#define RegistryDir "Software\Fractal Softworks\Starsector"

[Setup]
AppId={{7CCE286B-96B4-46A2-BC8C-EBD6FD70F3E6}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
AllowCancelDuringInstall=no
DefaultDirName={autopf}\Starsector
AppendDefaultDirName=false
SetupIconFile=translation_pack_with_game.ico
WizardSizePercent=100
WizardResizable=no
DisableProgramGroupPage=yes
DisableWelcomePage=no
OutputBaseFilename={#MyAppName} {#MyAppVersion} [{#MyAppPublisher}]
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
WizardSmallImageFile=Wizard_SmallImage/55x58_with_game.bmp
WizardImageFile=Wizard_Image_Whole.bmp
LicenseFile=GAME_LICENSE.txt
CreateUninstallRegKey=no
Uninstallable=no

[Languages]
Name: "chinesesimplified"; MessagesFile: ".\ChineseSimplified.isl"

[Dirs]
Name: "{app}"; Permissions: users-modify

[Files]
Source: "{#OriginalGameFolder}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Permissions: users-modify
Source: "{#TranslationProjectFolder}\localization\*"; Excludes:"rules分段"; DestDir: "{app}\{#TargetInstallFolder}"; Flags: ignoreversion recursesubdirs createallsubdirs; Permissions: users-modify

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}";     GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Icons]
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}";     Tasks: desktopicon
