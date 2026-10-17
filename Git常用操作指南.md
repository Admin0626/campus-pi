# Git 常用操作指南

> 适用场景：日常开发中下载代码、分支开发提交、冲突处理

---

## 一、下载文件至本地

### 1. 首次克隆仓库（远程 → 本地，全新下载）

```bash
# 把整个仓库克隆到本地（自动生成同名文件夹）
git clone https://github.com/Admin0626/campus-pi.git

# 克隆到指定文件夹
git clone https://github.com/Admin0626/campus-pi.git 本地文件夹名

# 克隆并只拉取某一个分支（如 main）
git clone -b main https://github.com/Admin0626/campus-pi.git
```

### 2. 更新已有仓库（同步远程最新代码）

```bash
# 查看当前远程地址
git remote -v

# 获取远程更新信息（只下载更新记录，不改动本地文件）
git fetch origin

# 拉取并合并到当前分支（最常用的更新方式）
git pull origin main

# 等价简写（当前分支已跟踪远程分支时）
git pull
```

### 3. 下载别人的分支到本地

```bash
# 先看远程有哪些分支
git fetch origin
git branch -a

# 直接切换到远程分支（Git 自动创建同名本地分支并建立跟踪）
git checkout feature-a

# 或手动创建并绑定
git checkout -b feature-a origin/feature-a
```

---

## 二、修改代码后为自己创建分支并提交

### 1. 从最新 main 切出自己的分支

```bash
# 先切回主分支并更新到最新，避免基于旧代码开发
git checkout main
git pull origin main

# 创建并切换到自己的分支（名字用 功能/姓名 命名，如 dev-zhangsan）
git checkout -b dev-你的名字

# 查看当前所在分支
git branch
```

### 2. 修改代码并提交到自己的分支

```bash
# 修改代码后，先查看改动了哪些文件
git status

# 暂存所有修改（也可指定单个文件：git add 文件名）
git add -A

# 提交到本地，写清楚改了什么
git commit -m "feat: 新增xxx功能"

# 第一次推送该分支，建立远程跟踪（之后只需 git push）
git push -u origin dev-你的名字
```

### 3. 后续在自己分支上继续开发

```bash
git add -A
git commit -m "fix: 修复xxx问题"
git push          # 直接推送，无需再写 -u
```

### 4. 开发完成后合并回 main

```bash
# 切回 main 并更新
git checkout main
git pull origin main

# 把你的分支合并进 main
git merge dev-你的名字

# 推送 main 到远程
git push origin main

# 合并完成，删除本地和远程的分支（可选）
git branch -d dev-你的名字
git push origin --delete dev-你的名字
```

---

## 三、冲突时怎么处理

冲突通常发生在：拉取代码或合并分支时，你和别人（或两个分支）**修改了同一个文件的同一处**。

### 1. 冲突是怎么出现的

```bash
git merge dev-b
# 输出类似：
# Auto-merging index.html
# CONFLICT (content): Merge conflict in index.html
# Automatic merge failed; fix conflicts and then commit the result.
```

### 2. 找出冲突文件

```bash
# 列出所有存在冲突的文件
git status

# 未合并状态（unmerged）的文件就是冲突文件
```

### 3. 打开冲突文件，手动解决

冲突处会有如下标记：

```
<<<<<<< HEAD
这里是当前分支（你自己的代码）
=======
这里是被合并进来的分支的代码
>>>>>>> dev-b
```

**处理方式：**
- 仔细阅读两段代码，**保留正确的部分**（或两者都要，按需整合）
- **删除** `<<<<<<<`、`=======`、`>>>>>>>` 这三类标记行
- 保存文件

### 4. 标记已解决并完成合并

```bash
# 将解决后的文件加入暂存区（表示冲突已解决）
git add 冲突文件名

# 完成这次合并提交
git commit -m "merge: 解决与 dev-b 在 index.html 的冲突"

# 推送结果
git push origin main
```

### 5. 放弃本次合并（想反悔时）

```bash
# 合并进行中想取消，恢复到合并前的状态
git merge --abort
```

### 6. 预防冲突的小技巧

```bash
# 开发前、提交前，先同步远程最新代码
git pull origin main

# 推送前先看看自己改了什么
git diff

# 小步提交、频繁推送，降低冲突概率
```

---

## 附：一条命令速查表

| 场景 | 命令 |
| --- | --- |
| 下载仓库 | `git clone <仓库地址>` |
| 更新代码 | `git pull origin main` |
| 查看改动 | `git status` / `git diff` |
| 建分支 | `git checkout -b 分支名` |
| 暂存修改 | `git add -A` |
| 提交 | `git commit -m "说明"` |
| 首推分支 | `git push -u origin 分支名` |
| 合并分支 | `git merge 分支名` |
| 解决冲突后提交 | `git add 文件` → `git commit` |
| 放弃合并 | `git merge --abort` |
| 删除分支 | `git branch -d 分支名` |
