#!/usr/bin/env bash
# 从系统 shell 配置中加载 LLM api-key 到当前环境。
# 用途：init.sh / mvn test 在非交互 shell 下运行时，继承用户 .zshrc 中已配置的密钥，
# 使凭据 fail-fast 校验（mc-p0-04）能拿到真实 key。
# 安全：只提取 export 行，不 source 整个配置文件；密钥不回显。
# 注意：本脚本以 source 方式使用，必须始终以成功状态返回（不用 exit）。
set -u

KEYS="DEEPSEEK_API_KEY DEEPSEEK_END_POINT DEEPSEEK_MODEL LOCAL_LLM_API_KEY GLM_API_KEY GLM_END_POINT GLM_MODEL GLM_EMBEDDING_API_KEY DASHSCOPE_API_KEY DASHSCOPE_EMBEDDING_API_KEY ALIYUN_LLM_API_KEY"

# 已存在的变量跳过
MISSING=()
for k in $KEYS; do
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
    if [ -z "${!k:-}" ]; then MISSING+=("$k"); fi
  done
  [ ${#MISSING[@]} -eq 0 ] && break
done

:  # 始终以成功状态返回，避免 source 场景下中断调用方（set -e）
