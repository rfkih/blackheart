#!/usr/bin/env powershell
# Systematic refactoring of manual null/empty checks to Spring utilities

param(
    [string]$Pattern = "*.java",
    [string]$BasePath = "src\main\java"
)

$patterns = @(
    # Pattern 1: Simple null checks on Objects -> ObjectUtils.isEmpty/isNotEmpty
    # if (obj == null) -> if (ObjectUtils.isEmpty(obj))
    @{ regex = '(\s)if\s*\(\s*(\w+)\s*==\s*null\s*\)'; replacement = '$1if (ObjectUtils.isEmpty($2))'; desc = "obj == null" },

    # if (obj != null) -> if (ObjectUtils.isNotEmpty(obj))
    @{ regex = '(\s)if\s*\(\s*(\w+)\s*!=\s*null\s*\)'; replacement = '$1if (ObjectUtils.isNotEmpty($2))'; desc = "obj != null" },

    # Pattern 2: Collection isEmpty checks
    # list.isEmpty() is already good, no change needed
    # list.size() > 0 -> !CollectionUtils.isEmpty(list)
    @{ regex = '(\w+)\.size\(\)\s*>\s*0'; replacement = '!CollectionUtils.isEmpty($1)'; desc = "list.size() > 0" },

    # list.size() == 0 -> CollectionUtils.isEmpty(list)
    @{ regex = '(\w+)\.size\(\)\s*==\s*0'; replacement = 'CollectionUtils.isEmpty($1)'; desc = "list.size() == 0" },

    # Pattern 3: String null/empty checks
    # str == null || str.isEmpty() -> StringUtils.isEmpty(str)
    @{ regex = '(\w+)\s*==\s*null\s*\|\|\s*\1\.isEmpty\(\)'; replacement = 'StringUtils.isEmpty($1)'; desc = "str == null || str.isEmpty()" },

    # !str.isEmpty() -> StringUtils.hasText(str) on strings
    @{ regex = '!\s*StringUtils\.isEmpty\((\w+)\)'; replacement = 'StringUtils.hasText($1)'; desc = "!StringUtils.isEmpty" },

    # Pattern 4: Stream filters with null
    # .filter(x -> x != null) -> use Objects.nonNull (already in Java)
    # .filter(Objects::nonNull) is fine, but verbose null checks can be simplified
)

function Add-Imports {
    param(
        [string]$FilePath,
        [string[]]$ImportsNeeded
    )

    $content = Get-Content $FilePath -Raw
    $added = $false

    foreach ($import in $ImportsNeeded) {
        if ($content -notmatch "import.*$import") {
            # Find the last import statement
            $lastImportMatch = [regex]::Matches($content, '^import\s+.*?;$', [System.Text.RegexOptions]::Multiline) | Select-Object -Last 1

            if ($lastImportMatch) {
                $insertPos = $lastImportMatch.Index + $lastImportMatch.Length
                $content = $content.Insert($insertPos, "`nimport $import;")
                $added = $true
            }
        }
    }

    if ($added) {
        Set-Content -Path $FilePath -Value $content -Encoding UTF8
        return $true
    }
    return $false
}

function Refactor-File {
    param(
        [string]$FilePath
    )

    $content = Get-Content $FilePath -Raw
    $original = $content
    $importsToAdd = @()
    $changesCount = 0

    # Check what utilities are already imported
    $hasObjectUtils = $content -match "import.*ObjectUtils"
    $hasCollectionUtils = $content -match "import.*CollectionUtils"
    $hasStringUtils = $content -match "import.*StringUtils"

    # Simple substitutions
    $testContent = $content

    # Pattern: obj == null -> ObjectUtils.isEmpty(obj)
    if ($testContent -match '\s+if\s*\(\s*\w+\s*==\s*null\s*\)' -and !$hasObjectUtils) {
        $importsToAdd += "org.springframework.util.ObjectUtils"
    }

    # Pattern: if (x != null) -> need ObjectUtils
    if ($testContent -match '\s+if\s*\(\s*\w+\s*!=\s*null\s*\)' -and !$hasObjectUtils) {
        $importsToAdd += "org.springframework.util.ObjectUtils"
    }

    # Add imports if needed
    if ($importsToAdd.Count -gt 0) {
        Add-Imports -FilePath $FilePath -ImportsNeeded $importsToAdd
        Write-Host "Added imports to $(Split-Path $FilePath -Leaf): $($importsToAdd -join ', ')"
    }

    # Count potential changes
    foreach ($pat in $patterns) {
        $matches = [regex]::Matches($testContent, $pat.regex, [System.Text.RegexOptions]::Multiline)
        if ($matches.Count -gt 0) {
            Write-Host "  Found $($matches.Count) instances of: $($pat.desc)"
            $changesCount += $matches.Count
        }
    }

    return $changesCount
}

# Main execution
$files = Get-ChildItem -Path $BasePath -Filter $Pattern -Recurse -File
$totalChanges = 0
$filesProcessed = 0

Write-Host "Scanning $($files.Count) Java files for null/empty check patterns..."
Write-Host ""

foreach ($file in $files) {
    $changes = Refactor-File -FilePath $file.FullName
    if ($changes -gt 0) {
        Write-Host "$(Split-Path $file.FullName -Leaf): $changes potential changes"
        $totalChanges += $changes
        $filesProcessed++
    }
}

Write-Host ""
Write-Host "Summary: $filesProcessed files with $totalChanges total potential changes"
Write-Host ""
Write-Host "Next steps:"
Write-Host "1. Review proposed changes in sample files"
Write-Host "2. Adjust regex patterns if needed"
Write-Host "3. Run full refactoring with actual replacements"
