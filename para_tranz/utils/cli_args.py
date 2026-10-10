"""数字命令的参数解析与分发，不加载项目配置或执行输入输出。"""

from dataclasses import dataclass
from typing import Callable, Mapping, Sequence


@dataclass(frozen=True)
class Command:
    option: str
    argument: str | None = None
    rebuild_jars: bool = False


def parse_args(argv: Sequence[str]) -> Command:
    if not argv:
        raise ValueError('未指定操作选项')
    option, *args = argv
    if option not in ('1', '2', '3', '4', '5', '6'):
        raise ValueError(f'无效选项：{option}')
    if option in ('2', '3'):
        if args not in ([], ['--rebuild-jars']):
            raise ValueError('导入选项仅支持可选参数 --rebuild-jars')
        return Command(option, rebuild_jars=args == ['--rebuild-jars'])
    if option not in ('4', '5') and '--rebuild-jars' in args:
        raise ValueError('--rebuild-jars 仅用于选项 2 或 3')
    # 保留原有 4/5 的首个字符串参数，以及其它选项忽略多余参数的行为。
    return Command(option, args[0] if args else None)


def dispatch_command(command: Command, actions: Mapping[str, Callable]) -> None:
    action = actions[command.option]
    if command.option in ('2', '3'):
        action(rebuild_jars=command.rebuild_jars)
    elif command.option in ('4', '5'):
        action(command.argument)
    else:
        action()
