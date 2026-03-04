import io
import json
import time
import zipfile
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from para_tranz.config import PARA_TRANZ_PATH, PARATRANZ_API_KEY, PARATRANZ_PROJECT_ID
from para_tranz.utils.util import make_logger

logger = make_logger('ParaTranzAPI')

_BASE_URL = 'https://paratranz.cn/api'
_POLL_INTERVAL = 10   # 轮询间隔（秒）
_POLL_TIMEOUT = 300   # 最长等待时间（秒）


def _request(path: str, method: str = 'GET') -> dict:
    req = Request(f'{_BASE_URL}{path}', method=method)
    req.add_header('Authorization', f'Bearer {PARATRANZ_API_KEY}')
    with urlopen(req) as resp:
        data = resp.read()
        return json.loads(data) if data else {}


def _get_artifact() -> dict:
    return _request(f'/projects/{PARATRANZ_PROJECT_ID}/artifacts')


def _trigger_export() -> bool:
    """触发平台导出。返回 True 表示触发成功，False 表示权限不足。"""
    try:
        _request(f'/projects/{PARATRANZ_PROJECT_ID}/artifacts', method='POST')
        logger.info('已触发平台导出')
        return True
    except HTTPError as e:
        if e.code in (401, 403):
            logger.info('当前用户无管理员权限，跳过触发导出，将直接下载最新导出文件')
            return False
        raise


def _poll_until_new_artifact(trigger_time: datetime) -> bool:
    """轮询直到出现比 trigger_time 更新的 artifact。超时返回 False。"""
    logger.info(f'等待平台导出完成（最多 {_POLL_TIMEOUT} 秒）...')
    deadline = time.monotonic() + _POLL_TIMEOUT
    while time.monotonic() < deadline:
        artifact = _get_artifact()
        created_at_str = artifact.get('createdAt', '')
        if created_at_str:
            created_at = datetime.fromisoformat(created_at_str.replace('Z', '+00:00'))
            if created_at > trigger_time:
                logger.info('导出已完成')
                return True
        time.sleep(_POLL_INTERVAL)
    logger.error(f'等待导出超时（{_POLL_TIMEOUT} 秒）')
    return False


def _download_and_extract() -> None:
    """下载导出 zip 并解压覆盖到 PARA_TRANZ_PATH，自动剥去 zip 内顶层文件夹。"""
    logger.info('正在下载导出文件...')
    req = Request(f'{_BASE_URL}/projects/{PARATRANZ_PROJECT_ID}/artifacts/download')
    req.add_header('Authorization', f'Bearer {PARATRANZ_API_KEY}')
    with urlopen(req) as resp:
        zip_bytes = resp.read()
    logger.info(f'下载完成（{len(zip_bytes) / 1024:.1f} KB），正在解压...')
    PARA_TRANZ_PATH.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        # zip 内顶层为单个文件夹（如 utf8/），剥去该层直接解压到 PARA_TRANZ_PATH
        names = zf.namelist()
        prefix = names[0].split('/')[0] + '/' if names else ''
        for member in zf.infolist():
            rel = member.filename
            if prefix and rel.startswith(prefix):
                rel = rel[len(prefix):]
            if not rel:  # 跳过顶层目录本身
                continue
            target = PARA_TRANZ_PATH / rel
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(zf.read(member.filename))
    logger.info(f'已解压到 {PARA_TRANZ_PATH}')


def download_paratranz_export() -> bool:
    """
    从 ParaTranz 平台下载项目导出文件并解压到 output 目录。

    流程：
    1. 尝试触发导出（需要管理员权限）
    2. 若触发成功，轮询等待新导出完成后下载
    3. 若权限不足，直接下载最新已有导出
    4. 解压覆盖 PARA_TRANZ_PATH

    返回是否成功。
    """
    if not PARATRANZ_PROJECT_ID or not PARATRANZ_API_KEY:
        logger.error('未配置 PARATRANZ_PROJECT_ID 或 PARATRANZ_API_KEY，请检查 .env 文件')
        return False

    trigger_time = datetime.now(timezone.utc)
    triggered = _trigger_export()
    if triggered and not _poll_until_new_artifact(trigger_time):
        return False

    _download_and_extract()
    return True