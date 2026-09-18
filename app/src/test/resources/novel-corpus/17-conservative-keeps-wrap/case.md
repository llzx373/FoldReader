# 17 保守档不做段落重组

- 输入与 `04-hard-wrap-reflow` 同型（硬换行 + 全角空格），但档位是 **CONSERVATIVE**。
- 期望：全角空格照删（`collapseSpaces` 属保守档），但**两行不会被合并**（`reflowParagraphs` 只在标准档及以上）。
- 用途：锁定「档位越高改动越多」这个不变量，同时证明档位覆盖（`profile.txt`）有效。
