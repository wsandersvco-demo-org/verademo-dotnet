import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.triggers.vcs

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2025.11"

project {

    buildType(VeracodePipelineScanSca)
}

object VeracodePipelineScanSca : BuildType({
    name = "Veracode Pipeline Scan + SCA"

    artifactRules = """
        +:veracode-artifacts => veracode-artifacts
        +:veracode-results => veracode-results
    """.trimIndent()

    params {
        param("VERACODE_POLICY_NAME", "Veracode Recommended Very High")
        param("VERACODE_CLI", "")
        param("env.DEBUG", "false")
    }

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        powerShell {
            name = "Download Veracode CLI"
            id = "Veracode_CLI"
            scriptMode = script {
                content = """
                    # Get the first file in the directory                   
                    ${'$'}cliFile = Get-ChildItem -File | Select-Object -First 1 -ExpandProperty Name
                    Write-Host "Filename: ${'$'}cliFile"                  
                                                                        
                    # Extract version from filename (get the second part when split by underscore)
                    ${'$'}parts = ${'$'}cliFile -split '_'                  
                    ${'$'}local_version = ${'$'}parts[1]                                                                                                                                                                                                 
                    Write-Host "Local version: ${'$'}local_version"
                                                                                                                                                                                                                                                
                    # Download and read LATEST_VERSION                                                                                                                                                                                         
                    Invoke-WebRequest -Uri "https://tools.veracode.com/veracode-cli/LATEST_VERSION" -OutFile "LATEST_VERSION"
                    ${'$'}latest_version = (Get-Content "LATEST_VERSION" -Raw).Trim()                                                                                                                                                               
                    Write-Host "Latest version: ${'$'}latest_version"
                    
                    if (${'$'}local_version -eq ${'$'}latest_version) {                                                                                                                                                                                  
                        Write-Host "We already have the latest version - nothing to do here"
                        Remove-Item -Path "LATEST_VERSION" -Force -ErrorAction SilentlyContinue                                                                                                                                                
                    } else {                               
                        Write-Host "There is a new version we need to download"
                        ${'$'}downloadUrl = "https://tools.veracode.com/veracode-cli/veracode-cli_${'$'}{latest_version}_linux_x86.tar.gz"
                        Write-Host "Download URL: ${'$'}downloadUrl"
                    
                        # Download the files
                        Invoke-WebRequest -Uri ${'$'}downloadUrl -OutFile "veracode-cli_${'$'}{latest_version}_linux_x86.tar.gz"
                        Invoke-WebRequest -Uri "https://tools.veracode.com/veracode-cli/install.ps1" -OutFile "veracode-cli_${'$'}{latest_version}_windows.ps1"
                    
                        # List files
                        Write-Host "Current files:"
                        Get-ChildItem -Force
                    
                        Write-Host "CLEAN UP"
                        Remove-Item -Path "veracode-cli_${'$'}{local_version}_linux_x86.tar.gz" -Force -ErrorAction SilentlyContinue
                        Remove-Item -Path "LATEST_VERSION" -Force -ErrorAction SilentlyContinue
                    
                        Write-Host "Files after cleanup:"
                        Get-ChildItem -Force
                    }
                """.trimIndent()
            }
        }
        powerShell {
            name = "Ensure MSBuild Available"
            id = "Ensure_MSBuild_Available"
            scriptMode = script {
                content = """
                    try {
                      ${'$'}msbuildAlreadyExists = Get-Command msbuild.exe -ErrorAction SilentlyContinue
                    } catch {
                      ${'$'}msbuildAlreadyExists = ${'$'}false
                    }
                    Write-Host "MSBuild install check: ${'$'}msbuildAlreadyExists"
                    if (-not ${'$'}msbuildAlreadyExists) {
                      ${'$'}vswherePath = "${'$'}{env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
                      try {
                        if ([System.IO.File]::Exists(${'$'}vswherePath)) {
                          Write-Host "vswherePath install check: ${'$'}vswherePath"
                          ${'$'}msbuildPath = & ${'$'}vswherePath -latest -requires Microsoft.Component.MSBuild -find MSBuild\**\Bin\MSBuild.exe
                          if (${'$'}msbuildPath) {
                            ${'$'}msbuildDir = [System.IO.Path]::GetDirectoryName(${'$'}msbuildPath)
                            Write-Host "msbuildDir install check: ${'$'}msbuildDir"
                    
                            # Persist path across steps
                            Write-Host "##teamcity[setParameter name='env.msbuildDir' value='${'$'}msBuildDir']"
                            Write-Host "MSBuild path exported to env.msbuildDir: ${'$'}msbuildDir"
                          }
                        }
                      } catch {
                        Write-Host "vswhere catch block executed."
                      }
                    }
                """.trimIndent()
            }
        }
        powerShell {
            name = "Install Veracode CLI"
            id = "Install_Veracode_CLI"
            scriptMode = script {
                content = """
                    ${'$'}cliFile = Get-ChildItem -Path "." -Filter *.ps1 | Select-Object -First 1
                    Write-Host "Found CLI install script: ${'$'}cliFile"
                    Set-ExecutionPolicy Bypass -Scope Process -Force
                    ${'$'}ProgressPreference = "silentlyContinue"
                    & ${'$'}cliFile.FullName
                    ${'$'}VERACODE_CLI = Get-Command veracode | Select-Object -ExpandProperty Definition
                    Write-Host "##teamcity[setParameter name='VERACODE_CLI' value='${'$'}VERACODE_CLI']"
                """.trimIndent()
            }
        }
        powerShell {
            name = "Veracode Package"
            id = "Veracode_Package"
            scriptMode = script {
                content = """
                    ${'$'}working_path = (Get-Location).Path
                    ${'$'}packageArgs = @(
                      "package",
                      "--source", "${'$'}working_path",
                      "--output", "veracode-artifacts",
                      "--trust"
                    )
                    
                    if ("%env.DEBUG%" -eq "true") {
                      ${'$'}packageArgs += "--verbose"
                    }
                    
                    Write-Host "Running: veracode ${'$'}(${'$'}packageArgs -join ' ')"
                    & %VERACODE_CLI% @packageArgs
                """.trimIndent()
            }
        }
        powerShell {
            name = "Veracode Static Scan"
            id = "Veracode_Static_Scan"
            scriptMode = script {
                content = """
                    ${'$'}files = (Get-ChildItem -Depth 1 -Path ./veracode-artifacts/ -File)
                    Write-Host "Files for matrix"
                    foreach (${'$'}file in ${'$'}files) {
                      Write-Host ${'$'}file.name
                    }
                    
                    New-Item -Path 'veracode-results' -Force -Type Directory
                    
                    ${'$'}policyArgs = @(
                      "policy",
                      "get",
                      "%VERACODE_POLICY_NAME%",
                      "--format", "json"
                    )
                    
                    & %VERACODE_CLI% @policyArgs
                    
                    ${'$'}scanArgs = @(
                      "static",
                      "scan",
                      "--policy-file", "%VERACODE_POLICY_NAME%.json"
                    )
                    
                    if ("%env.DEBUG%" -eq 'true') {
                    	${'$'}scanArgs += '--verbose'
                    }
                    
                    ${'$'}count = 0
                    ${'$'}exited = @()
                    foreach (${'$'}file in ${'$'}files) {
                    	Write-Host "Current file: ${'$'}(${'$'}file.fullname)"
                        ${'$'}currentScanArgs = ${'$'}scanArgs
                        ${'$'}currentScanArgs += @("--results-file", "`"veracode-results/${'$'}(${'$'}count)-results.json`"")
                        ${'$'}currentScanArgs += @("--filtered-json-output-file", "`"veracode-results/${'$'}(${'$'}count)-filtered-results.json`"")
                        ${'$'}currentScanArgs += "`"${'$'}(${'$'}file.fullname)`""
                        Write-Host "Running: veracode ${'$'}(${'$'}currentScanArgs -join ' ')"
                    	& %VERACODE_CLI% @currentScanArgs
                        Write-Host "Result code: ${'$'}LASTEXITCODE"
                        ${'$'}exited += ${'$'}LASTEXITCODE
                        ${'$'}count++
                    }
                    
                    if (${'$'}exited -contains 3) { Write-Host "Build gated, flaws found" ; exit 1 }
                """.trimIndent()
            }
        }
        powerShell {
            name = "Veracode SCA Scan"
            id = "Veracode_SCA_Scan"
            scriptMode = script {
                content = """
                    #Invoke-Command -ScriptBlock ([scriptblock]::Create([System.Text.Encoding]::UTF8.GetString((New-Object Net.WebClient).DownloadData('https://sca-downloads.veracode.com/ci.ps1')))) -ArgumentList @('--recursive', '--allow-dirty', '--appname', '"%env.TEAMCITY_PROJECT_NAME%"')
                    New-Item -Path 'veracode-results' -Type Directory -Force
                    Invoke-Command -ScriptBlock ([scriptblock]::Create([System.Text.Encoding]::UTF8.GetString((New-Object Net.WebClient).DownloadData('https://sca-downloads.veracode.com/ci.ps1')))) -ArgumentList @('scan', '--recursive', '--allow-dirty', '--json', 'veracode-results\scaResults.json')
                """.trimIndent()
            }
        }
        step {
            name = "Veracode Policy Scan"
            id = "Veracode_Policy_Scan"
            type = "teamcity-veracode-plugin"
            param("deleteIncompleteScan", "1")
            param("teams", "Default Team")
            param("appName", "%env.TEAMCITY_PROJECT_NAME%")
            param("criticality", "VeryHigh")
            param("useGlobalCredentials", "true")
            param("version", "%env.BUILD_NUMBER%")
            param("uploadIncludePattern", "veracode-artifacts/**")
            param("createProfile", "true")
            param("waitForScan", "true")
            param("createSandbox", "false")
            param("scanTimeOut", "120")
        }
    }

    triggers {
        vcs {
        }
    }

    features {
        perfmon {
        }
    }
})
