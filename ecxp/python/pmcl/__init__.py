"""PMCL 的 Python 接口。

由启动器拉起时读取 PMCL_ECXP_ENDPOINT 和 PMCL_ECXP_TOKEN，
走和 ECXP 相同的本机协议：一行一个 JSON。
方法名和插件 API 一致，例如 versions.listLocalVersions()。
返回值里没有账号令牌。删除类方法能不能用，由应用声明的权限决定。
"""

from pmcl.api import Pmcl, connect
from pmcl.client import PmclError, hosted

__all__ = ["Pmcl", "PmclError", "connect", "hosted"]
