# -*- coding: utf-8 -*-
"""javadoc / 注释卫生检查（不引 Maven 插件，纯脚本，可证伪）。

三条规则，每条都能指出具体的文件:行号：

  R1  注释里出现 Markdown 粗体 `**x**`      —— Javadoc 不渲染 Markdown，会原样显示星号。
  R2  裸的决策/缺陷编号 `D1` / `D4`          —— 各里程碑的决策编号互不共享，缺陷与决策也共用过同一编号；
                                               一律写 `BUG-Dn` / `M4 决策 n`。
  R3  成员级 javadoc 里有参数却无 @param     —— Javadoc 摘要之外，参数语义只能从代码里猜。

退出码：0 = 全绿；1 = 有违规。

用法：
    python docs/check-javadoc.py            # 只报告
    python docs/check-javadoc.py --quiet    # 只打印汇总
"""
from __future__ import annotations

import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SRC_ROOTS = [
    r'aihub-gateway\src\main\java',
    r'aihub-gateway\src\test\java',
    r'aihub-admin\aihub-common\src\main\java',
    r'aihub-admin\aihub-common\src\test\java',
    r'aihub-admin\aihub-service\src\main\java',
    r'aihub-admin\aihub-web\src\main\java',
    r'aihub-admin\aihub-web\src\test\java',
    r'aihub-admin\aihub-dao\src\main\java',
    r'aihub-admin\aihub-mq\src\main\java',
]

# {@code ...} / {@link ...} / {@literal ...} / {@value ...} 内部不参与检查
INLINE_TAG = re.compile(r'\{@(?:code|link|linkplain|literal|value)\b[^}]*\}', re.S)
BARE_D = re.compile(r'(?<![A-Za-z0-9_\-])D(\d{1,2})(?![0-9])')
# 成员级方法签名的参数表（粗略但够用：javadoc 后第一个非空行若是方法/构造器声明）
METHOD_SIG = re.compile(r'^\s*(?:public|protected|private|static|final|abstract|synchronized|\s)*'
                        r'[\w<>\[\], .?]+\s+(\w+)\s*\(([^)]*)\)', re.M)


def iter_java_files():
    for root in SRC_ROOTS:
        base = os.path.join(REPO, root)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != 'target']
            for name in sorted(filenames):
                if name.endswith('.java'):
                    yield os.path.join(dirpath, name)


def javadoc_blocks(lines):
    """[(起始行下标, 结束行下标)] 0-based，含定界符。"""
    blocks = []
    i, n = 0, len(lines)
    while i < n:
        if re.match(r'^\s*/\*\*', lines[i]):
            if lines[i].find('*/', lines[i].find('/**') + 3) >= 0:
                blocks.append((i, i))
                i += 1
                continue
            e = i + 1
            while e < n and '*/' not in lines[e]:
                e += 1
            blocks.append((i, min(e, n - 1)))
            i = e + 1
        else:
            i += 1
    return blocks


def blank_out_inline_tags(text: str) -> str:
    """把 {@code ...} 等替换成等长空白，保持列号不变。"""
    return INLINE_TAG.sub(lambda m: ' ' * len(m.group(0)), text)


def check():
    violations = {'R1': [], 'R2': [], 'R2-legacy': [], 'R3': []}
    files = 0
    blocks = 0
    for path in iter_java_files():
        files += 1
        rel = os.path.relpath(path, REPO).replace('\\', '/')
        raw = open(path, 'rb').read().decode('utf-8')
        lines = raw.split('\n')
        for (s, e) in javadoc_blocks(lines):
            blocks += 1
            seg_lines = lines[s:e + 1]
            # 去掉首行 `/**` 与末行 `*/` 之外的定界符残片
            seg = '\n'.join(seg_lines)
            masked = blank_out_inline_tags(seg).replace('/**', '   ', 1)

            # ---- R1: 注释里的 Markdown 粗体
            # 例外：路径通配符 `/api/**` —— 那是字面量，不是 Markdown。
            for off, line in enumerate(masked.split('\n')):
                hit = None
                for m in re.finditer(r'\*\*', line):
                    if m.start() > 0 and line[m.start() - 1] == '/':
                        continue
                    hit = m.start()
                    break
                if hit is not None:
                    violations['R1'].append((rel, s + off + 1, line.strip()[:110]))
                    break

            # ---- R2: 裸 Dn
            # 硬失败只针对 D1/D4：这两个编号在本仓库历史上**同时**被用作缺陷名与决策号，
            # 而各里程碑的决策编号都从 1 重新开始 ⇒ 同一个 `D4` 能指三件不相干的事。
            # 其余裸 Dn（D5..D17）目前是**存量债务**（M4 计划第 4 节 23 条决策的引用），
            # 本轮不批量改写（会打歪 docs/ 里 170 处行号锚点），只在汇总里报数量。
            for off, line in enumerate(masked.split('\n')):
                m = BARE_D.search(line)
                if m:
                    entry = (rel, s + off + 1, line.strip()[:110])
                    if m.group(1) in ('1', '4'):
                        violations['R2'].append(entry)
                    else:
                        violations['R2-legacy'].append(entry)
                    break

            # ---- R3: 成员级 javadoc 缺 @param
            # 只查"长得像说明文"的块（>= 6 行）：一行 getter / record 组件的 javadoc 不写
            # @param 是合理的；一条要求几百处无意义改动的规则会被直接关掉，等于没有。
            if (e - s + 1) < 6:
                continue
            after = ''
            for k in range(e + 1, min(e + 6, len(lines))):
                after += lines[k] + '\n'
                if '{' in lines[k]:
                    break
            sig = METHOD_SIG.search(after)
            if sig:
                params = [p.strip() for p in sig.group(2).split(',') if p.strip()]
                if params:
                    joined = '\n'.join(lines[s:e + 1])
                    missing = []
                    for p in params:
                        name = p.split()[-1].replace('...', '').strip()
                        if not name or name in ('final',):
                            continue
                        if not re.search(r'@param\s+' + re.escape(name) + r'\b', joined):
                            missing.append(name)
                    if missing:
                        violations['R3'].append(
                            (rel, s + 1, '%s(...) 缺 @param %s' % (sig.group(1), ', '.join(missing))))
    return files, blocks, violations


def main():
    quiet = '--quiet' in sys.argv
    files, blocks, v = check()
    print('files scanned   : %d' % files)
    print('javadoc blocks  : %d' % blocks)
    for rule, desc in (('R1', '注释里的 Markdown 粗体 **x**（Javadoc 不渲染）'),
                       ('R2', '裸的 D1/D4（应为 BUG-Dn 或 M<里程碑> 决策 n）'),
                       ('R2-legacy', '裸的其它 Dn（存量债务，不硬失败）'),
                       ('R3', '成员级 javadoc 有参数却缺 @param（>=6 行的块）')):
        print('%-10s %-48s : %d' % (rule, desc, len(v[rule])))
    print('HARD violations (R1+R2): %d' % (len(v['R1']) + len(v['R2'])))
    if not quiet:
        for rule in ('R1', 'R2', 'R3'):
            for rel, line, ctx in v[rule][:40]:
                print('  %s %s:%d  %s' % (rule, rel, line, ctx))
            if len(v[rule]) > 40:
                print('  %s ... 另有 %d 处' % (rule, len(v[rule]) - 40))
    # R2-legacy 与 R3 是「达标线」而非硬失败：都是历史存量，一次改不完，
    # 而且 R2-legacy 的批量改写会打歪 docs/ 里 170 处行号锚点。
    # R1 / R2 是硬失败 —— 纯机械的两类，改完就不该再回来。
    hard = len(v['R1']) + len(v['R2'])
    return 0 if hard == 0 else 1


if __name__ == '__main__':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.exit(main())
