param(
    [string]$DataYaml = (Join-Path $PSScriptRoot '..\collector\runtime\dataset\exports\yolo_v1\data.yaml'),
    [string]$Weights = 'yolo11n.pt',
    [int]$Epochs = 100,
    [int]$ImageSize = 448,
    [string]$RunName = 'person_body',
    [string]$ProjectDir = (Join-Path $PSScriptRoot '..\runs\deltavision'),
    [string]$ExportDir = (Join-Path $PSScriptRoot '..\models\trained\person_body'),
    [switch]$PushToPhone,
    [string]$PhoneModelPath = '/sdcard/Android/data/com.deltavision.app/files/models/model.onnx'
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$venvRoot = Join-Path $repoRoot '.venv-train'
$pythonExe = Join-Path $venvRoot 'Scripts\python.exe'
$requirements = Join-Path $PSScriptRoot 'requirements-train.txt'
$trainScript = Join-Path $PSScriptRoot 'train_yolo.py'
$exportScript = Join-Path $PSScriptRoot 'export_ncnn.py'

if (!(Test-Path $DataYaml)) {
    throw "Dataset config not found: $DataYaml. Export YOLO dataset from Collector first."
}

if (!(Test-Path $pythonExe)) {
    python -m venv $venvRoot
}

& $pythonExe -m pip install --upgrade pip
& $pythonExe -m pip install -r $requirements

& $pythonExe $trainScript $DataYaml --weights $Weights --imgsz $ImageSize --epochs $Epochs --project $ProjectDir --name $RunName

$bestPt = Join-Path $ProjectDir "$RunName\weights\best.pt"
if (!(Test-Path $bestPt)) {
    throw "best.pt not found after training: $bestPt"
}

New-Item -ItemType Directory -Force -Path $ExportDir | Out-Null
& $pythonExe $exportScript $bestPt --imgsz $ImageSize --output $ExportDir

$onnxFile = Get-ChildItem -Path $ExportDir -Filter *.onnx | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $onnxFile) {
    throw "??????? ONNX ???$ExportDir"
}

$modelFile = Join-Path $ExportDir 'model.onnx'
Copy-Item -Force $onnxFile.FullName $modelFile
Write-Host "Model generated: $modelFile"

if ($PushToPhone) {
    adb push $modelFile $PhoneModelPath
    Write-Host "???????$PhoneModelPath"
}
