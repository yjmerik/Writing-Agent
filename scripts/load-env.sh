#!/usr/bin/env bash
# 从系统 shell 配置中加载 LLM api-key 到当前环境。
# 用途：init.sh / mvn test 在非交互 shell 下运行时，继承用户 .zshrc 中已配置的密钥，
# 使凭据 fail-fast 校验（mc-p0-04）能拿到真实 key。
# 安全：只提取 export 行，不 source 整个配置文件；密钥不回显。
# 注意：本脚本以 source 方式使用，必须始终以成功状态返回（不用 exit）。
set -u

KEYS="DEEPSEEK_API_KEY DEEPSEEK_END_POINT DEEPSEEK_MODEL LOCAL_LLM_API_KEY GLM_API_KEY GLM_END_POINT GLM_MODEL GLM_EMBEDDING_API_KEY DASHSCOPE_API_KEY DASHSCOPE_EMBEDDING_API_KEY ALIYUN_LLM_API_KEY DASHSCOPE_TOKEN_PLAN_API_KEY DASHSCOPE_TOKEN_PLAN_BASE_URL DASHSCOPE_TOKEN_PLAN_MODEL"

# 已存在的变量跳过；向量专用密钥显式为空也必须保留。
MISSING=()
for k in $KEYS; do
  if [ "$k" = "DASHSCOPE_EMBEDDING_API_KEY" ] && [ "${DASHSCOPE_EMBEDDING_API_KEY+x}" = x ]; then continue; fi
  if [ -z "${!k:-}" ]; then MISSING+=("$k"); fi
done

CONFIG_FILES=("$HOME/.zshenv" "$HOME/.zprofile" "$HOME/.zshrc" "$HOME/.bash_profile" "$HOME/.profile")
for f in "${CONFIG_FILES[@]}"; do
  [ -f "$f" ] || continue
  while IFS= read -r line; do
    # 匹配 export KEY=... （允许单双引号）
    for k in "${MISSING[@]}"; do
      case "$line" in
        "export $k="*|"export $k='"*|"export $k=\""*)
          eval "export $line"
          ;;
      esac
    done
  done < "$f"
  # 重新计算缺失项
  MISSING=()
  for k in $KEYS; do
    if [ "$k" = "DASHSCOPE_EMBEDDING_API_KEY" ] && [ "${DASHSCOPE_EMBEDDING_API_KEY+x}" = x ]; then continue; fi
  if [ -z "${!k:-}" ]; then MISSING+=("$k"); fi
  done
  [ ${#MISSING[@]} -eq 0 ] && break
done

# 套餐密钥（sk-sp-）不能用于按量付费向量接口。仅在没有可用环境变量时，
# 复用本机百炼 CLI 已保存的按量付费凭据；不改通用密钥，不向终端回显。
if [ "${DASHSCOPE_EMBEDDING_API_KEY+x}" != x ]; then
  case "${DASHSCOPE_API_KEY:-}" in
    ""|sk-sp-*)
      if [ -f "$HOME/.bailian/config.json" ] && command -v python3 >/dev/null 2>&1; then
        embedding_profile_key="$(python3 - "$HOME/.bailian/config.json" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as profile:
        key = json.load(profile).get("api_key", "")
    if isinstance(key, str) and key.startswith("sk-") and not key.startswith("sk-sp-"):
        print(key, end="")
except (OSError, ValueError, AttributeError):
    pass
PY
)"
        if [ -n "$embedding_profile_key" ]; then
          export DASHSCOPE_EMBEDDING_API_KEY="$embedding_profile_key"
        fi
        unset embedding_profile_key
      fi
      ;;
  esac
fi

# Token Plan 专用环境变量优先；否则只读复用 ZCode 已有的套餐凭据。
# 不回退到通用/向量/DeepSeek key，避免串用不同计费通道；不修改 ZCode。
if [ -z "${DASHSCOPE_TOKEN_PLAN_API_KEY:-}" ] && [ -f "$HOME/.zcode/v2/config.json" ]; then
  if command -v python3 >/dev/null 2>&1; then
    token_plan_profile_key="$(python3 - "$HOME/.zcode/v2/config.json" <<'PY'
import json, sys
from urllib.parse import urlparse
try:
    with open(sys.argv[1]) as source:
        providers = json.load(source).get("provider", {})
    keys = set()
    for provider in providers.values():
        options = provider.get("options", {})
        url = urlparse(options.get("baseURL", ""))
        key = options.get("apiKey", "")
        if (url.scheme == "https" and url.hostname == "token-plan.cn-beijing.maas.aliyuncs.com"
                and "qwen3.8-flash" in provider.get("models", {})
                and isinstance(key, str) and key.startswith("sk-sp-")):
            keys.add(key)
    if len(keys) == 1:
        print(next(iter(keys)), end="")
except (OSError, ValueError, AttributeError, TypeError):
    pass
PY
)"
    if [ -n "$token_plan_profile_key" ]; then
      export DASHSCOPE_TOKEN_PLAN_API_KEY="$token_plan_profile_key"
    fi
    unset token_plan_profile_key
  fi
fi

:  # 始终以成功状态返回，避免 source 场景下中断调用方（set -e）
