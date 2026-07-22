#requires -Version 7.5

[CmdletBinding()]
param(
    [Parameter()]
    [string] $ProjectRoot = (
        Resolve-Path (Join-Path $PSScriptRoot '../../../..')
    ).Path,

    [Parameter()]
    [ValidateSet('Generate', 'Inventory', 'Scaffold')]
    [string] $Mode = 'Generate',

    [Parameter()]
    [switch] $Strict
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$JavaRoot = Join-Path $ProjectRoot 'MinutiaeEnforcement/src/main/java/org/synergyst/minutiae/lang'
$SiteRoot = Join-Path $ProjectRoot 'MinutiaeEnforcement/site'
$OutputRoot = Join-Path $SiteRoot 'en/docs/alam/diag'
$FactsPath = Join-Path $PSScriptRoot 'diag-facts.json'
$TemplatePath = Join-Path $PSScriptRoot 'diagnostic.template.html'
$InventoryPath = Join-Path $PSScriptRoot 'diagnostic-inventory.json'

function ConvertTo-HtmlText {
    param([AllowNull()][object] $Value)

    if ($null -eq $Value) {
        return ''
    }

    return [System.Net.WebUtility]::HtmlEncode([string] $Value)
}

function Get-LineNumber {
    param(
        [Parameter(Mandatory)]
        [string] $Text,

        [Parameter(Mandatory)]
        [int] $Offset
    )

    if ($Offset -le 0) {
        return 1
    }

    return 1 + (
        [regex]::Matches(
            $Text.Substring(0, $Offset),
            "`n"
        ).Count
    )
}

function Get-DiagnosticPhase {
    param(
        [Parameter(Mandatory)]
        [string] $Code
    )

    switch ($Code[0]) {
        'L' { return 'Lexing' }
        'P' { return 'Parsing' }
        'E' { return 'Elaboration' }
        'V' { return 'Evaluation' }
        'R' { return 'Verification' }
        'W' { return 'Warning' }
        default { throw "Unknown diagnostic family: $Code" }
    }
}

function Get-DiagnosticSeverity {
    param(
        [Parameter(Mandatory)]
        [string] $Code,

        [Parameter()]
        [string] $CallName
    )

    if ($Code.StartsWith('W') -or $CallName -match 'warning') {
        return 'Warning'
    }

    return 'Error'
}

function New-CodeRange {
    param(
        [Parameter(Mandatory)]
        [char] $Prefix,

        [Parameter(Mandatory)]
        [int] $First,

        [Parameter(Mandatory)]
        [int] $Last,

        [Parameter()]
        [string[]] $Exclude = @()
    )

    foreach ($Number in $First..$Last) {
        $Code = '{0}{1:D3}' -f $Prefix, $Number

        if ($Code -notin $Exclude) {
            $Code
        }
    }
}

function Get-PublicDiagnosticCodes {
    @(
        New-CodeRange -Prefix 'L' -First 1 -Last 8
        New-CodeRange -Prefix 'P' -First 1 -Last 13 -Exclude 'P012'
        New-CodeRange -Prefix 'E' -First 1 -Last 37
        New-CodeRange -Prefix 'V' -First 1 -Last 17
        New-CodeRange -Prefix 'R' -First 1 -Last 5
        'W001'
        'W003'
    )
}

function Get-JavaDiagnosticInventory {
    if (-not (Test-Path $JavaRoot)) {
        throw "Java language source directory not found: $JavaRoot"
    }

    $Occurrences = [System.Collections.Generic.List[object]]::new()

    # Covers:
    #   error("E007", ...)
    #   report("P009", ...)
    #   diags.error("L004", ...)
    #   diags.warning("W001", ...)
    #
    # Codes are intentionally required to be string literals. A computed
    # diagnostic code would fail inventory and should be avoided.
    $Pattern = [regex]::new(
        '(?ms)' +
        '(?<receiver>\b(?:diags|diagnostics)\s*\.\s*)?' +
        '(?<call>error|warning|report)\s*' +
        '\(\s*"' +
        '(?<code>[LPEVRW]\d{3})"'
    )

    Get-ChildItem -Path $JavaRoot -Recurse -Filter '*.java' |
        Sort-Object FullName |
        ForEach-Object {
            $File = $_
            $Text = Get-Content -LiteralPath $File.FullName -Raw

            foreach ($Match in $Pattern.Matches($Text)) {
                $Code = $Match.Groups['code'].Value
                $Call = $Match.Groups['call'].Value
                $Relative = [IO.Path]::GetRelativePath(
                    $ProjectRoot,
                    $File.FullName
                ).Replace('\', '/')

                $Occurrences.Add([ordered]@{
                    code = $Code
                    phase = Get-DiagnosticPhase -Code $Code
                    severity = Get-DiagnosticSeverity `
                        -Code $Code `
                        -CallName $Call
                    source = $Relative
                    className = $File.BaseName
                    line = Get-LineNumber `
                        -Text $Text `
                        -Offset $Match.Index
                    call = $Call
                })
            }
        }

    return $Occurrences
}

function Read-Facts {
    if (-not (Test-Path $FactsPath)) {
        return @{}
    }

    $Raw = Get-Content -LiteralPath $FactsPath -Raw

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return @{}
    }

    return $Raw | ConvertFrom-Json -AsHashtable
}

function Convert-ParagraphsToHtml {
    param(
        [AllowNull()]
        [object] $Paragraphs
    )

    if ($null -eq $Paragraphs) {
        return ''
    }

    $Items = @($Paragraphs)

    return (
        $Items |
            ForEach-Object {
                '<p>{0}</p>' -f (ConvertTo-HtmlText $_)
            }
    ) -join "`n"
}

function Convert-RelatedToScript {
    param(
        [AllowNull()]
        [object] $Related
    )

    if ($null -eq $Related) {
        return 'var ME_RELATED = [];'
    }

    $Json = @($Related) |
        ConvertTo-Json -Depth 8 -Compress

    return "var ME_RELATED = $Json;"
}

function Convert-OccurrencesToHtml {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [object[]] $Occurrences
    )

    if ($Occurrences.Count -eq 0) {
        return '<p>This code is not emitted by the current compiler.</p>'
    }

    $Rows = foreach ($Occurrence in $Occurrences) {
        $Source = ConvertTo-HtmlText $Occurrence.source
        $Class = ConvertTo-HtmlText $Occurrence.className
        $Line = [int] $Occurrence.line

        @"
<tr>
    <td><code>$Class</code></td>
    <td><code>$Source</code></td>
    <td>$Line</td>
</tr>
"@
    }

    return @"
<table>
    <tr>
        <th>Class</th>
        <th>Source</th>
        <th>Line</th>
    </tr>
$($Rows -join "`n")
</table>
"@
}

function Get-DefaultFact {
    param(
        [Parameter(Mandatory)]
        [string] $Code,

        [Parameter(Mandatory)]
        [bool] $Emitted
    )

    if ($Emitted) {
        return [ordered]@{
            title = 'Undocumented emitted diagnostic'
            canonicalMessage = ''
            cause = @(
                'This code is emitted by the current compiler, but its curated documentation entry has not been written yet.'
            )
            example = $null
            exampleLanguage = 'alam'
            remedy = @(
                'Inspect the emission site listed on this page and add a curated entry to diag-facts.json.'
            )
            related = @()
            reserved = $false
        }
    }

    return [ordered]@{
        title = 'Reserved diagnostic'
        canonicalMessage = ''
        cause = @(
            'This code is reserved for a future compiler diagnostic and is not emitted by the current implementation.'
        )
        example = $null
        exampleLanguage = 'alam'
        remedy = @(
            'No action is required. Use the documentation version matching the compiler that produced the diagnostic.'
        )
        related = @(
            [ordered]@{
                label = 'Compilation pipeline'
                href = '../guide/pipeline.html'
                group = 'See also'
            }
        )
        reserved = $true
    }
}

function Merge-Fact {
    param(
        [Parameter(Mandatory)]
        [hashtable] $Default,

        [AllowNull()]
        [hashtable] $Override
    )

    $Merged = [ordered]@{}

    foreach ($Key in $Default.Keys) {
        $Merged[$Key] = $Default[$Key]
    }

    if ($null -ne $Override) {
        foreach ($Key in $Override.Keys) {
            $Merged[$Key] = $Override[$Key]
        }
    }

    return $Merged
}

function Convert-ExampleToHtml {
    param(
        [AllowNull()]
        [string] $Example,

        [Parameter()]
        [string] $Language = 'alam'
    )

    if ([string]::IsNullOrWhiteSpace($Example)) {
        return @'
<p>No triggering example is defined for this diagnostic.</p>
'@
    }

    $Encoded = ConvertTo-HtmlText $Example
    $LanguageClass = ConvertTo-HtmlText $Language

    return @"
<pre><code class="language-$LanguageClass">$Encoded</code></pre>
"@
}

function Expand-Template {
    param(
        [Parameter(Mandatory)]
        [string] $Template,

        [Parameter(Mandatory)]
        [hashtable] $Values
    )

    $Result = $Template

    foreach ($Entry in $Values.GetEnumerator()) {
        $Token = '{{' + $Entry.Key + '}}'
        $Result = $Result.Replace($Token, [string] $Entry.Value)
    }

    $Unresolved = [regex]::Matches(
        $Result,
        '\{\{[A-Z0-9_]+\}\}'
    )

    if ($Unresolved.Count -gt 0) {
        $Tokens = (
            $Unresolved |
                ForEach-Object Value |
                Sort-Object -Unique
        ) -join ', '

        throw "Unresolved template tokens: $Tokens"
    }

    return $Result
}

$PublicCodes = @(Get-PublicDiagnosticCodes)
$Occurrences = @(Get-JavaDiagnosticInventory)

$InventoryByCode = @{}

foreach ($Code in $PublicCodes) {
    $InventoryByCode[$Code] = @(
        $Occurrences |
            Where-Object code -EQ $Code
    )
}

$UnknownEmitted = @(
    $Occurrences |
        Where-Object code -NotIn $PublicCodes |
        Select-Object -ExpandProperty code -Unique
)

if ($UnknownEmitted.Count -gt 0) {
    throw (
        'Java emits codes outside the declared public space: ' +
        ($UnknownEmitted -join ', ')
    )
}

$InventoryDocument = foreach ($Code in $PublicCodes) {
    $Sites = @($InventoryByCode[$Code])

    [ordered]@{
        code = $Code
        phase = Get-DiagnosticPhase -Code $Code
        emitted = $Sites.Count -gt 0
        occurrences = $Sites
    }
}

$InventoryDocument |
    ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath $InventoryPath -Encoding utf8

if ($Mode -eq 'Inventory') {
    Write-Host "Inventory written to $InventoryPath"
    return
}

$Facts = Read-Facts

if ($Mode -eq 'Scaffold') {
    $Scaffold = [ordered]@{}

    foreach ($Entry in $InventoryDocument) {
        $Code = $Entry.code

        if (-not $Facts.ContainsKey($Code)) {
            $Scaffold[$Code] = Get-DefaultFact `
                -Code $Code `
                -Emitted ([bool] $Entry.emitted)
        }
    }

    $ScaffoldPath = Join-Path $PSScriptRoot 'diag-facts.scaffold.json'
    $Scaffold |
        ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $ScaffoldPath -Encoding utf8

    Write-Host "Fact scaffold written to $ScaffoldPath"
    return
}

if (-not (Test-Path $TemplatePath)) {
    throw "Template not found: $TemplatePath"
}

$Template = Get-Content -LiteralPath $TemplatePath -Raw
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

$MissingCurated = [System.Collections.Generic.List[string]]::new()

foreach ($Entry in $InventoryDocument) {
    $Code = [string] $Entry.code
    $IsEmitted = [bool] $Entry.emitted
    $Default = Get-DefaultFact -Code $Code -Emitted $IsEmitted

    $Override = $null
    if ($Facts.ContainsKey($Code)) {
        $Override = [hashtable] $Facts[$Code]
    } elseif ($IsEmitted) {
        $MissingCurated.Add($Code)
    }

    $Fact = Merge-Fact -Default $Default -Override $Override

    $Reserved = if ([bool] $Fact.reserved) {
        'Reserved'
    } else {
        'Emitted'
    }

    $CanonicalMessage = if (
        [string]::IsNullOrWhiteSpace(
            [string] $Fact.canonicalMessage
        )
    ) {
        '<span class="muted">Not specified</span>'
    } else {
        '<code>{0}</code>' -f (
            ConvertTo-HtmlText $Fact.canonicalMessage
        )
    }

    $Values = @{
        CODE = ConvertTo-HtmlText $Code
        TITLE = ConvertTo-HtmlText $Fact.title
        PHASE = ConvertTo-HtmlText $Entry.phase
        SEVERITY = if ($Code.StartsWith('W')) {
            'Warning'
        } else {
            'Error'
        }
        STATUS = $Reserved
        CANONICAL_MESSAGE = $CanonicalMessage
        CAUSE = Convert-ParagraphsToHtml $Fact.cause
        EXAMPLE = Convert-ExampleToHtml `
            -Example $Fact.example `
            -Language $Fact.exampleLanguage
        REMEDY = Convert-ParagraphsToHtml $Fact.remedy
        EMISSION_SITES = Convert-OccurrencesToHtml `
            -Occurrences @($Entry.occurrences)
        RELATED_SCRIPT = Convert-RelatedToScript $Fact.related
    }

    $Html = Expand-Template `
        -Template $Template `
        -Values $Values

    $OutputPath = Join-Path $OutputRoot "$Code.html"
    Set-Content -LiteralPath $OutputPath -Value $Html -Encoding utf8
}

if ($Strict -and $MissingCurated.Count -gt 0) {
    throw (
        'Emitted diagnostics without curated facts: ' +
        ($MissingCurated -join ', ')
    )
}

Write-Host (
    "Generated {0} diagnostic pages in {1}" -f
    $PublicCodes.Count,
    $OutputRoot
)

if ($MissingCurated.Count -gt 0) {
    Write-Warning (
        'Emitted diagnostics using generated placeholder facts: ' +
        ($MissingCurated -join ', ')
    )
}