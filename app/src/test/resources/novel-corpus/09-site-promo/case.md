# 09 站点宣传语（问题 9）

- **现象**：正文之间夹着一行「请记住本站域名 www.example-novel.com」。
- **期望**：整行删除（同时命中站点推广短语与 URL 两条规则）。
- **档位**：STANDARD（`filterNoise`）。
- **相关**：行内夹带（广告与正文同一行）见 `15-inline-watermark`，那是 `exciseInlineNoise` 的活。
