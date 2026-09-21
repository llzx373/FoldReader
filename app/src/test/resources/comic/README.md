# 漫画容器样本

`pages.cbr` 是 **WinRAR 7.23 打出来的真实 RAR5 归档**（`rar a -m0`，即仅存储），
供 `ComicArchiveExtractorTest` 覆盖 rar 解压路径。

## 为什么这里放了一个二进制文件

本仓库的惯例是测试素材现场生成、不进仓库（见 `ComicTestImages`）。图片可以这样，
RAR 不行：RAR 是专有格式，没有可用的 Java 写入库，而 7-Zip 只读不写；
WinRAR 7.x 更是已经移除了创建 RAR4 的能力（`rar.exe -?` 里没有 `-ma` 开关）。
所以只保留这一个已归档好的样本。

## 内容

| 条目 | 内容 | 用途 |
| --- | --- | --- |
| `1.png` | `1.png` | 页 |
| `10.png` | `10.png` | 页（验证 1 < 2 < 10 的自然序，而非字典序） |
| `2.png` | `2.png` | 页 |
| `readme.txt` | `readme.txt` | 噪音：非图片 |
| `Thumbs.db` | `Thumbs.db` | 噪音：缩略图缓存 |
| `__MACOSX/._1.png` | `junk` | 噪音：macOS 资源叉 |
| `__MACOSX` | （目录条目） | 噪音：目录 |

每个页条目的内容就是它自己的名字，断言可以直接比对，不需要再嵌一份字节常量。

## 重新生成

```sh
mkdir -p /tmp/stage/__MACOSX
printf '1.png'   > /tmp/stage/1.png
printf '10.png'  > /tmp/stage/10.png
printf '2.png'   > /tmp/stage/2.png
printf 'readme.txt' > /tmp/stage/readme.txt
printf 'Thumbs.db'  > /tmp/stage/Thumbs.db
printf 'junk'    > /tmp/stage/__MACOSX/._1.png
cd /tmp/stage && rar a -m0 /path/to/pages.cbr '*'
```

改完样本要同步更新 `ComicArchiveExtractorTest` 里对应的断言。
