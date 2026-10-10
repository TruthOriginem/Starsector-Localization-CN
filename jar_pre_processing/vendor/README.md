# jar-string-decoupler

- 源码仓库：[jnxyp/jar-string-decoupler](https://github.com/jnxyp/jar-string-decoupler)。
- 版本：[1.0.1](https://github.com/jnxyp/jar-string-decoupler/releases/tag/v1.0.1)，
  源码提交：[`4f18d97`](https://github.com/jnxyp/jar-string-decoupler/commit/4f18d97b9db2b1d0077a3050107719654848c8ba)。
- 产物：`jar-string-decoupler-1.0.1-all.jar`。
- 构建：在源码仓库使用 JDK 17，设置 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`，
  运行 `mvnw.cmd clean package`，将 `target/` 下的 fat Jar 复制到此目录。
- SHA-256：`3904df667ed49cdc3b31019b47df8983216b5a1d2ced82b7c54c37c1231240ce`。

此版本修复字符串解耦后 `StackMapTable` 中未初始化对象的指令偏移，并检查其是否指向 `new`。
保留原有常量池索引及 class 版本；结构检查不替代 JVM 完整类型验证。
