# Git Cheat Sheet for Absolute Beginners

## Quick Start

### First-time setup (do this once)

#### 1. Clone this repository

```bash
# Open Command Prompt, PowerShell, Terminal, or Git Bash and run:
git clone https://github.com/Danial-Afiq/tech-advisor.git
cd tech-advisor
```

## What is Git?

Git helps multiple people work on the same code without overwriting each other's work. Think of it like version control for the whole project.

## Basic concepts

### Repository (repo)

- The project folder that Git tracks.
- A local copy lives on your computer.
- The remote repository lives on GitHub.

### Branch

- A separate line of work for a feature, fix, or other change.
- `main` is the shared official branch.
- Do **not** develop directly on `main`.

Common branch naming in this project:

```text
feature/user-profile
feature/device-management
fix/login-validation
chore/update-ci
docs/update-readme
```

### Commit

- Saves a checkpoint of your changes locally with a message.

### Push

- Uploads your local commits/branch to GitHub.

### Pull

- Downloads and integrates the latest changes from a remote branch.

### Pull request (PR)

- A request to merge your branch into another branch, usually `main`.
- Our GitHub Actions checks and code review happen here before merge.

### Merge

- Combines changes from one branch into another.
- For normal project work: your branch -> pull request -> `main`.

## Commands you'll use regularly

### 1. Starting new work

```bash
# Switch to main
git switch main

# Get the latest main
git pull origin main

# Create and switch to a new branch
git switch -c feature/your-feature-name
```

Use the appropriate prefix depending on the work:

```text
feature/...  new functionality
fix/...      bug fix
chore/...    setup / maintenance
 docs/...     documentation
```

### 2. Saving and pushing your work

```bash
# See what changed
git status

# Stage all changes
git add .

# OR stage a specific file
git add path/to/file

# Commit your changes
git commit -m "feat: describe what you changed"

# Push the branch to GitHub (first push)
git push -u origin feature/your-feature-name
```

After pushing, open a **pull request into `main`** on GitHub. The PR must pass the required GitHub Actions checks and reviews before it can be merged.

### 3. Switching branches

```bash
# See local branches
git branch

# Switch branch
git switch branch-name

# Switch back to main
git switch main
```

## Common scenarios

### Scenario 1: Starting a new feature

```bash
git switch main
git pull origin main
git switch -c feature/new-feature

# ... make your changes ...

git add .
git commit -m "feat: add new feature"
git push -u origin feature/new-feature
```

Then open a PR from `feature/new-feature` into `main`.

### Scenario 2: Main changed while you were working

```bash
# Make sure your current work is committed first

git switch main
git pull origin main
git switch feature/your-feature
git merge main
```

If there are conflicts, resolve them in your editor, then:

```bash
git add .
git commit -m "fix: resolve merge conflict"
git push
```

### Scenario 3: I made a mistake

Before committing, restore one file:

```bash
git restore filename
```

Before committing, discard **all tracked local changes**:

```bash
git reset --hard
```

Undo the latest local commit but keep its changes:

```bash
git reset --soft HEAD~1
```

Undo the latest local commit and discard its changes:

```bash
git reset --hard HEAD~1
```

> `git reset --hard` is destructive. Make sure you do not need the uncommitted changes before using it.

## Understanding common Git messages

### "Your branch is up to date"

Good — your local branch matches its tracked remote branch.

### "Your branch is ahead by X commits"

You have local commits that have not been pushed yet. Run:

```bash
git push
```

### "Your branch is behind by X commits"

The remote branch contains newer commits. Usually run:

```bash
git pull
```

### "Merge conflict"

Two changes overlap and Git cannot safely choose which version to keep. You need to resolve it manually.

## Fixing merge conflicts

Git may mark a conflict like this:

```text
<<<<<<< HEAD
Your code
=======
Their code
>>>>>>> main
```

1. Open the conflicted file in VS Code / your editor.
2. Decide what the final code should be.
3. Remove the `<<<<<<<`, `=======`, and `>>>>>>>` conflict markers.
4. Save the file.
5. Stage and commit the resolution:

```bash
git add .
git commit -m "fix: resolve merge conflict"
git push
```

## Best practices

### DO

- Pull the latest `main` before starting a new branch.
- Create a separate branch for each story / feature / fix.
- Commit meaningful chunks of work.
- Write clear commit messages.
- Push your branch regularly.
- Open a PR and let CI + reviewers check the change.

### DON'T

- Work directly on `main`.
- Commit real secrets or `.env` files.
- Merge while required CI checks are failing.
- Write vague commit messages such as `fixed stuff`.
- Force push (`git push -f`) unless you understand the consequences and the team has agreed to it.

## Quick reference

| I want to... | Command |
| --- | --- |
| See changed files | `git status` |
| Get latest main | `git switch main` then `git pull origin main` |
| Create new branch | `git switch -c feature/name` |
| Switch branch | `git switch branch-name` |
| Stage all changes | `git add .` |
| Commit changes | `git commit -m "message"` |
| Push first time | `git push -u origin branch-name` |
| Push later changes | `git push` |
| See local branches | `git branch` |
| Discard tracked local changes | `git reset --hard` |

## Common errors

### `fatal: not a git repository`

You're probably not inside the cloned project folder. Change directory into `tech-advisor` first.

### `error: failed to push`

The remote branch may have commits you do not have. Read the full Git error first; you may need to pull and resolve any conflicts before pushing again.

### `Please commit or stash your changes`

You have local changes blocking the operation. Either commit them or temporarily stash them:

```bash
git stash
```

Restore stashed changes later with:

```bash
git stash pop
```

### `pathspec 'branch' did not match`

Git cannot find that branch/path. Check the spelling and list your branches with:

```bash
git branch
```

## Still confused?

1. Read the full Git error message first.
2. Ask the team before running destructive commands if you are unsure.
3. Use VS Code's Source Control view or GitHub Desktop if a visual workflow helps.

Most Git mistakes are recoverable, but commands such as `git reset --hard` and force-pushing can discard work, so use them carefully.
