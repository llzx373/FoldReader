# 网络小说智能清理 · 测试语料

每个子目录是一个用例，`input.txt` 是脏数据、`expected.txt` 是期望的清洗结果。
`NovelCorpusTest` 遍历所有用例逐个断言——`expected.txt` 就是这套规则的**规格**。

## 目录约定

```
<NN>-<slug>/
├── input.txt       # 输入（必填）
├── expected.txt    # 期望输出（必填）
├── profile.txt     # 档位覆盖（可选）
└── case.md         # 人类可读的说明（可选）
```

`negatives/` 下的用例是**反向用例**：`expected.txt` 必须与 `input.txt` 完全一致，
用来防过度清洗（这是本功能最大的风险）。

## profile.txt

不写则用默认的 `STANDARD` 档。写了的话是逐行 `key=value`：

```
level=AGGRESSIVE              # CONSERVATIVE / STANDARD / AGGRESSIVE
off=reflowParagraphs,normalizeQuotes
on=traditionalToSimplified
```

`off` / `on` 里的名字就是 `CleanToggles` 的属性名（逗号分隔，可换行写多行）。

## 行尾与换行

仓库 `.gitattributes` 强制 LF，所以**不要**用 `input.txt` 测 CRLF——那类用例写在
`NovelCleanerTest` 里用代码内联构造。行尾空白（问题 2）不受影响，可以正常写在文件里。

## 新增用例

1. 建目录 `NN-slug/`；
2. 写 `input.txt`（真实的脏数据，别为了好断言而简化）；
3. 写 `expected.txt`（**先想清楚应该是什么**，再看实现对不对——反了就变成让实现定义规格）；
4. 如果这条能过，顺手往 `negatives/` 加一条「相似但不该被改」的干净文本。
