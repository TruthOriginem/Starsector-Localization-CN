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
OutputBaseFilename={#MyAppName} v{#MyAppVersion}{#OutputSuffix} [{#MyAppPublisher}]
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
; settings.json 不整体覆盖——玩家可能改过分辨率、音量、mod 相关等设置，直接覆盖会丢失。
; 改为安装后由 PatchSettingsJson 就地打补丁（见 [Code]），只动汉化必需的三处。
Source: "{#TranslationProjectFolder}\localization\*"; Excludes:"rules分段,data\config\settings.json"; DestDir: "{app}\{#TargetInstallFolder}"; Flags: ignoreversion recursesubdirs createallsubdirs
; 中文设计类型颜色片段（UTF-8）。放 {tmp} 供脚本读取原始字节后插入——
; 若在脚本里直接写中文字面量，AnsiString 赋值会经系统代码页转换而乱码。
Source: "settings_patch\designTypeColors_zh.txt"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Code]
{ ── settings.json 就地打补丁 ────────────────────────────────────────────────
  汉化相对原版只需三处改动：
    1. cjkMode                 中日韩断行支持，不开则中文不换行
    2. showCNTranslationCredits 汉化组署名
    3. designTypeColors        追加中文 key；缺失不会崩溃，但设计类型标签会退回默认色

  全程使用 AnsiString：Inno 的 LoadStringFromFile / SaveStringToFile 按原始字节
  读写，UTF-8 内容得以透传；若改用 String（Unicode）会在转换时损坏中文。
  任何一步失败都只警告并跳过，不阻断安装——设置没打上顶多显示异常，
  而写坏玩家的 settings.json 会导致游戏起不来。 }

const
  { 首尾标记须与 make_exe.py 中的常量一致 }
  PatchMarker = '# CN-DESIGN-TYPE-COLORS';
  PatchEndMarker = '# CN-DESIGN-TYPE-COLORS-END';
  DesignTypeKey = '"designTypeColors"';

{ 在单行内把 "key" 的值由 OldVal 改为 NewVal，容忍冒号前后任意空白
  （"k":v / "k" : v / "k":  v 均可）。该行不含此键、或值不是 OldVal
  （玩家改过、或已是目标值）时返回 False 且不改动。

  只在单行范围内做偏移计算——每行都很短且这两个键所在行为纯 ASCII，
  不会踩到整串操作在中文处错位的坑。 }
function ReplaceJsonValueInLine(var Line: String;
                                const Key, OldVal, NewVal: String): Boolean;
var
  P: Integer;
begin
  Result := False;
  P := Pos(Key, Line);
  if P = 0 then
    Exit;
  P := P + Length(Key);
  while (P <= Length(Line)) and ((Line[P] = ' ') or (Line[P] = #9)) do
    Inc(P);
  if (P > Length(Line)) or (Line[P] <> ':') then
    Exit;
  Inc(P);
  while (P <= Length(Line)) and ((Line[P] = ' ') or (Line[P] = #9)) do
    Inc(P);
  if Copy(Line, P, Length(OldVal)) <> OldVal then
    Exit;
  Line := Copy(Line, 1, P - 1) + NewVal + Copy(Line, P + Length(OldVal), Length(Line));
  Result := True;
end;

{ 从形如 <TAB><TAB>"键名":[...] 的行中取出含引号的键名（"键名"），
  取不到返回空串。用于判断该条目是否已存在。 }
function ExtractQuotedKey(const Line: String): String;
var
  A, B: Integer;
begin
  Result := '';
  A := Pos('"', Line);
  if A = 0 then
    Exit;
  B := A + 1;
  while (B <= Length(Line)) and (Line[B] <> '"') do
    Inc(B);
  if B > Length(Line) then
    Exit;
  Result := Copy(Line, A, B - A + 1);
end;

{ 判断 Key 是否已作为条目出现在 Lines 中（在 designTypeColors 块内外都算——
  同名键重复会让游戏只认其一，宁可漏插也不能插重）。 }
function KeyPresent(const Lines: TArrayOfString; const Key: String): Boolean;
var
  I: Integer;
begin
  Result := True;
  for I := 0 to GetArrayLength(Lines) - 1 do
    if Pos(Key + ':', Lines[I]) > 0 then
      Exit;
  Result := False;
end;

{ 在 Lines 的 InsertAt 处插入 Extra 中的各行，返回新数组。 }
function InsertLines(const Lines, Extra: TArrayOfString;
                     InsertAt: Integer): TArrayOfString;
var
  N, M, I: Integer;
  Res: TArrayOfString;
begin
  N := GetArrayLength(Lines);
  M := GetArrayLength(Extra);
  SetArrayLength(Res, N + M);
  for I := 0 to InsertAt - 1 do
    Res[I] := Lines[I];
  for I := 0 to M - 1 do
    Res[InsertAt + I] := Extra[I];
  for I := InsertAt to N - 1 do
    Res[M + I] := Lines[I];
  Result := Res;
end;

{ 把行数组以 CRLF 拼接后写为「无 BOM 的 UTF-8」。

  SaveStringsToUTF8File 会写 BOM，而游戏的 JSON 解析器不接受——实测加 BOM 后
  启动即 JSONException。故先让它写到临时文件，再按字节读回、去掉开头 3 字节
  BOM，最后以 AnsiString 落盘。去 BOM 用的是文件开头的固定偏移，前面没有中文，
  不受 AnsiString 索引语义的影响。 }
function SaveLinesAsUtf8NoBom(const Path: String;
                              const Lines: TArrayOfString): Boolean;
var
  TmpPath: String;
  Raw: AnsiString;
begin
  Result := False;
  TmpPath := ExpandConstant('{tmp}\settings_new.json');
  DeleteFile(TmpPath);
  if not SaveStringsToUTF8File(TmpPath, Lines, False) then
    Exit;
  if not LoadStringFromFile(TmpPath, Raw) then
    Exit;
  { 逐字节比 Ord，不能写成 Copy(Raw,1,3) = #$EF+#$BB+#$BF —— 左侧是 AnsiString
    的原始字节，右侧那串拼出来是 Unicode String（U+00EF/U+00BB/U+00BF），跨类型
    比较永不相等，BOM 会留在文件里，游戏读 settings.json 时直接 JSONException。 }
  if (Length(Raw) >= 3) and (Ord(Raw[1]) = $EF) and (Ord(Raw[2]) = $BB)
     and (Ord(Raw[3]) = $BF) then
    Raw := Copy(Raw, 4, Length(Raw));
  Result := SaveStringToFile(Path, Raw, False);
  DeleteFile(TmpPath);
end;

{ 只在影响中文排版时才打扰玩家：cjkMode 没设上会导致中文不换行，必须告知；
  署名开关与颜色条目失败都不影响可读性，仅记日志。 }
procedure WarnPatchFailed(const Reason: String);
begin
  Log('[settings.json] 补丁未应用：' + Reason);
  MsgBox('汉化安装成功。' + #13#10#13#10
    + '更新 游戏目录\{#TargetInstallFolder}\data\config\settings.json 失败：'
    + #13#10 + Reason + #13#10#13#10
    + '建议手动在文件中设置 "cjkMode":true，否则可能会影响中文排版。',
    mbInformation, MB_OK);
end;

procedure PatchSettingsJson();
var
  Path, BakPath, FragPath, Key: String;
  Lines, FragLines, Missing: TArrayOfString;
  Probe: AnsiString;
  I, DtcIdx, NumMissing, OrigCount: Integer;
  Changed, Restored: Boolean;
begin
  Path := ExpandConstant('{app}\{#TargetInstallFolder}\data\config\settings.json');
  if not FileExists(Path) then begin
    WarnPatchFailed('未找到该文件，游戏目录可能不完整。');
    Exit;
  end;
  { 行级读写而非整串操作：文件含大量中文，AnsiString 上的位置算术会错位
    （实测切在条目中间），而按行处理只做字符串比较与数组拼接，不涉及偏移。
    LoadStringsFromFile 原生识别 UTF-8。 }
  if not LoadStringsFromFile(Path, Lines) then begin
    WarnPatchFailed('读取失败，可能是权限不足或文件被占用（请关闭游戏后重装）。');
    Exit;
  end;
  OrigCount := GetArrayLength(Lines);
  if OrigCount < 100 then begin
    WarnPatchFailed('文件内容异常（仅 ' + IntToStr(OrigCount) + ' 行），已跳过以免损坏。');
    Exit;
  end;

  Changed := False;
  DtcIdx := -1;
  for I := 0 to OrigCount - 1 do begin
    if ReplaceJsonValueInLine(Lines[I], '"cjkMode"', 'false', 'true') then begin
      Changed := True;
      Log('[settings.json] cjkMode 已设为 true');
    end;
    if ReplaceJsonValueInLine(Lines[I], '"showCNTranslationCredits"',
                              'false', 'true') then begin
      Changed := True;
      Log('[settings.json] showCNTranslationCredits 已设为 true');
    end;
    if (DtcIdx < 0) and (Pos(DesignTypeKey, Lines[I]) > 0) then
      DtcIdx := I;
  end;

  { 颜色条目只增不减：逐条比对键名，缺哪条补哪条，已有的一律不动。
    译文改动（改名、新增势力）会作为新键补入；旧键留着不影响——游戏按键取色，
    多余的键取不到就用不上。这样无需定位并删除旧块，规避了整串偏移问题。 }
  FragPath := ExpandConstant('{tmp}\designTypeColors_zh.txt');
  if DtcIdx < 0 then begin
    Log('[settings.json] 未找到 designTypeColors，跳过颜色条目');
  end else if not LoadStringsFromFile(FragPath, FragLines) then begin
    Log('[settings.json] 颜色片段读取失败，跳过');
  end else begin
    NumMissing := 0;
    SetArrayLength(Missing, GetArrayLength(FragLines));
    for I := 0 to GetArrayLength(FragLines) - 1 do begin
      Key := ExtractQuotedKey(FragLines[I]);
      { 只补形如 "键":[...] 的条目行；片段里的注释与空行不进目标文件 }
      if (Key <> '') and (Pos('[', FragLines[I]) > 0)
         and (not KeyPresent(Lines, Key)) then begin
        Missing[NumMissing] := FragLines[I];
        NumMissing := NumMissing + 1;
      end;
    end;
    SetArrayLength(Missing, NumMissing);
    if NumMissing = 0 then
      Log('[settings.json] 颜色条目已齐备，无需补充')
    else begin
      Lines := InsertLines(Lines, Missing, DtcIdx + 1);
      Changed := True;
      Log('[settings.json] 补充了 ' + IntToStr(NumMissing) + ' 条颜色条目');
    end;
  end;

  if not Changed then begin
    Log('[settings.json] 无需改动（已是汉化配置）');
    Exit;
  end;
  { 行数只应增不减 }
  if GetArrayLength(Lines) < OrigCount then begin
    WarnPatchFailed('改写结果异常（' + IntToStr(OrigCount) + ' → '
      + IntToStr(GetArrayLength(Lines)) + ' 行），已跳过以免损坏。');
    Exit;
  end;

  { 先探测可写性再动手：只读或被占用时若照常建备份，CopyFile 会连只读属性一起
    复制，事后 DeleteFile 删不掉，白白在玩家目录里留个 .bak。追加空串不改变
    文件内容，是无副作用的探针。 }
  if not SaveStringToFile(Path, '', True) then begin
    WarnPatchFailed('文件不可写，可能是权限不足、只读或正被占用（请关闭游戏后重装）。'
      + '原文件未被改动。');
    Exit;
  end;

  { SaveStringToFile 是先截断再写，中途失败（磁盘满、杀软拦截、断电）会留下残缺
    文件，游戏直接起不来。故先备份，写完立即回读校验，不符即回滚。 }
  BakPath := Path + '.bak';
  DeleteFile(BakPath);
  if not CopyFile(Path, BakPath, False) then begin
    WarnPatchFailed('无法创建备份文件，已跳过修改以免损坏原文件。');
    Exit;
  end;

  if not SaveLinesAsUtf8NoBom(Path, Lines) then begin
    Restored := CopyFile(BakPath, Path, False);
    if Restored then begin
      DeleteFile(BakPath);
      WarnPatchFailed('写入中断，原文件已从备份恢复。');
    end else
      WarnPatchFailed('写入中断且自动恢复失败。原文件的完整副本在同目录下的'
        + ' settings.json.bak，请手动改名为 settings.json。');
    Exit;
  end;

  { 回读校验：行数一致 + 首字节不是 BOM。游戏的 JSON 解析器不接受 BOM，读到就
    直接 JSONException、进不去游戏；而写回链路里 SaveStringsToUTF8File 恰恰强制
    写 BOM，靠 SaveLinesAsUtf8NoBom 剥掉，故此处兜底确认它确实被剥干净了。 }
  if not LoadStringFromFile(Path, Probe) then
    Probe := '';
  if (Length(Probe) >= 3) and (Ord(Probe[1]) = $EF) and (Ord(Probe[2]) = $BB)
     and (Ord(Probe[3]) = $BF) then begin
    Restored := CopyFile(BakPath, Path, False);
    if Restored then
      DeleteFile(BakPath);
    WarnPatchFailed('写入结果带 BOM，游戏将无法读取，已恢复原文件。');
    Exit;
  end;
  if (not LoadStringsFromFile(Path, FragLines))
     or (GetArrayLength(FragLines) < GetArrayLength(Lines)) then begin
    Restored := CopyFile(BakPath, Path, False);
    if Restored then begin
      DeleteFile(BakPath);
      WarnPatchFailed('写入结果校验未通过，原文件已从备份恢复。');
    end else
      WarnPatchFailed('写入结果校验未通过且自动恢复失败。原文件的完整副本在同目录下的'
        + ' settings.json.bak，请手动改名为 settings.json。');
    Exit;
  end;

  DeleteFile(BakPath);
  Log('[settings.json] 补丁已应用（' + IntToStr(OrigCount) + ' → '
    + IntToStr(GetArrayLength(Lines)) + ' 行）');
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    PatchSettingsJson();
end;

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
