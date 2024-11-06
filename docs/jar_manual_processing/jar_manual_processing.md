# Jar文件手动处理记录
对于以下两种情况，我们需要手动编辑jar文件中的数据或代码。

本文件用于追踪当前版本jar文件的手动处理记录。

1. 需要翻译的string对应的UTF-8常量同时被其它代码元素引用，无法直接替换。
2. 游戏本身的代码逻辑需要修改，以适应翻译后的文本。

## UTF-8常量被string以外的元素引用
| 文件路径 | 原文 |
|---|---|
| starfarer_obf.jar:<br/>com/fs/starfarer/campaign/CharacterStats.class | `points` |
| starfarer_obf.jar:<br/>com/fs/starfarer/coreui/x.class | `max` |
| starfarer_obf.jar:<br/>com/fs/starfarer/launcher/opengl/GLLauncher.class | `fullscreen` |
| starfarer_obf.jar:<br/>com/fs/starfarer/launcher/opengl/GLLauncher.class | `sound` |
| starfarer_obf.jar:<br/>com/fs/starfarer/ui/newui/X.class | `next` |
| starfarer.api.jar:<br/>com/fs/starfarer/api/impl/campaign/intel/group/FleetGroupIntel.class | `fleets` |

## 代码逻辑修改

### 1. 存档页面存档难度文本
相关文件：`starfarer_obf.jar:com/fs/starfarer/campaign/save/LoadGameDialog$o.class`
![difficulty_ui.png](difficulty_ui.png)

这里的 `Normal` 是直接读取了 SaveGameData 的 difficulty 属性，可能的值为 `normal` 和 `easy`，并令其开头大写。
![difficulty_code-1.png](difficulty_code-1.png)
两个常量在 `starfarer.api.jar:com/fs/starfarer/api/impl/campaign/ids/Difficulties.class` 中定义
而该属性同时用作难度的id，所以不便直接翻译常量的值。需要添加处理逻辑来把难度id映射到中文难度。

### 2. 舰船信息页文本换行前缺少最后一个字
![line_end_char_missing-1.png](line_end_char_missing-1.png)
![line_end_char_missing-2.png](line_end_char_missing-2.png)
可能与换行算法有关，待查。