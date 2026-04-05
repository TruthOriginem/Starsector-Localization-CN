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
; INCLUDE_DATE=true -> OutputSuffix，例： 2026.04.05（含前导空格）
#ifndef OutputSuffix
  #define OutputSuffix ""
#endif

#define GameBaseName "Starsector(远行星号)"
#define TranslationPackName " " + GameVersion + " 独立汉化包"
#define MyAppName GameBaseName + TranslationPackName + TranslationPackVarient
#define MyAppPublisher "远星汉化组"
#define MyAppURL "https://www.fossic.org/"
#define TranslationProjectFolder ".."
#define TargetInstallFolder "starsector-core"
#define RegistryDir "Software\Fractal Softworks\Starsector"

[Setup]
AppId={{D7DF7DD8-1A31-4435-8BAD-CB53E191C59F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
AllowCancelDuringInstall=no
DefaultDirName={reg:HKCU\{#RegistryDir},|{reg:HKLM\{#RegistryDir},|{autopf}\Starsector}}
DirExistsWarning=no
AppendDefaultDirName=false
SetupIconFile=translation_pack.ico
WizardSizePercent=100
WizardResizable=no
DisableProgramGroupPage=yes
DisableWelcomePage=no
OutputBaseFilename={#MyAppName} {#MyAppVersion}{#OutputSuffix} [{#MyAppPublisher}]
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
WizardSmallImageFile=Wizard_SmallImage/55x58.bmp
WizardImageFile=Wizard_Image_Pack.bmp
LicenseFile=PACK_LICENSE.txt
CreateUninstallRegKey=no
Uninstallable=no

[Languages]
Name: "chinesesimplified"; MessagesFile: ".\ChineseSimplified.isl"

[Messages]
chinesesimplified.SelectDirDesc= [name] 必须安装在 {#GameBaseName} 根目录下。
chinesesimplified.BrowseDialogLabel = 在下列列表中选择 {#GameBaseName} 安装文件夹，然后点击"确定"。

[Files]
Source: "{#TranslationProjectFolder}\localization\*"; Excludes:"rules分段"; DestDir: "{app}\{#TargetInstallFolder}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Code]
function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Log('NextButtonClick(' + IntToStr(CurPageID) + ') called');
  Result := True;
  case CurPageID of
    wpSelectDir:
    if not DirExists(ExpandConstant('{app}\{#TargetInstallFolder}')) then begin
      Log(ExpandConstant('{app}\{#TargetInstallFolder}'));
      MsgBox('请选择带有 {#TargetInstallFolder} 文件夹的 {#GameBaseName} 根目录！', mbError, MB_OK);
      Result := False;
    end;
  end;
end;
