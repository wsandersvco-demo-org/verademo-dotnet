package _Self.buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.vcs

object Build : BuildType({
    name = "Build"

    params {
        checkbox("env.VERACODE_DEBUG", "",
                  checked = "true", unchecked = "false")
    }

    vcs {
        root(HttpsGithubComWsandersvcoDemoOrgVerademoDotnetRefsHeadsMain)
    }

    steps {
        script {
            name = "Veracode Package"
            id = "Veracode_Package"
            scriptContent = """
                #!/usr/bin/env bash
                
                curl -sSO https://tools.veracode.com/veracode-cli/LATEST_VERSION
                latest_version=${'$'}(<"LATEST_VERSION")
                echo "Latest version: ${'$'}latest_version"
                
                mkdir -p /opt/buildagent/veracode-cli/
                cd /opt/buildagent/veracode-cli/
                
                # Check if any CLI files exist to determine local version
                if ls veracode-cli_*_linux_x86.tar.gz 1> /dev/null 2>&1; then
                  cliFile=${'$'}(ls -1vr veracode-cli_*_linux_x86.tar.gz | head -n 1)
                  echo "Filename: ${'$'}cliFile"
                  local_version="${'$'}{cliFile#*_}"
                  local_version="${'$'}{local_version%%_*}"
                  echo "Local version: ${'$'}local_version"
                else
                  local_version=""
                  echo "No local CLI files found - will download the latest version"
                fi
                
                curl -sSO https://tools.veracode.com/veracode-cli/LATEST_VERSION
                latest_version=${'$'}(<"LATEST_VERSION")
                echo "Latest version: ${'$'}latest_version"
                
                # Always clean up old versions (keep only the latest version)
                echo "Cleaning up old CLI versions (keeping only ${'$'}latest_version)..."
                
                # Single loop to remove all files except the latest Linux and Windows versions
                for file in veracode-cli_*; do
                  if [[ -f "${'$'}file" && "${'$'}file" != "veracode-cli_${'$'}{latest_version}_linux_x86.tar.gz" && "${'$'}file" != "veracode-cli_${'$'}{latest_version}_windows_x86.zip" && "${'$'}file" != "veracode-cli_${'$'}{latest_version}_windows.ps1" ]]; then
                    # Additional check: if this is a PS1 file, only remove it if the corresponding ZIP exists
                    if [[ "${'$'}file" == *"_windows.ps1" ]]; then
                      expected_zip="veracode-cli_${'$'}{latest_version}_windows_x86.zip"
                      if [[ -f "${'$'}expected_zip" ]]; then
                        echo "Removing old PS1 file: ${'$'}file (ZIP version exists)"
                        git rm -f "${'$'}file" 2>/dev/null || rm -f "${'$'}file"
                      else
                        echo "Keeping PS1 file: ${'$'}file (no ZIP version available yet)"
                      fi
                    else
                      echo "Removing old CLI file: ${'$'}file"
                      git rm -f "${'$'}file" 2>/dev/null || rm -f "${'$'}file"
                    fi
                  fi
                done
                
                if [[ -n "${'$'}local_version" && "${'$'}local_version" == "${'$'}latest_version" ]]; then
                  echo "We already have the latest version (${'$'}local_version) - nothing to download"
                  rm -rf LATEST_VERSION
                else
                  if [[ -n "${'$'}local_version" ]]; then
                    echo "There is a new version available (local: ${'$'}local_version, latest: ${'$'}latest_version)"
                  else
                    echo "No local version exists - downloading latest version (${'$'}latest_version)"
                  fi
                
                  # Download the new version (both Linux and Windows binaries)
                  linuxUrl="https://tools.veracode.com/veracode-cli/veracode-cli_${'$'}{latest_version}_linux_x86.tar.gz"
                  windowsUrl="https://tools.veracode.com/veracode-cli/veracode-cli_${'$'}{latest_version}_windows_x86.zip"
                  echo "Downloading Linux CLI: ${'$'}linuxUrl"
                  curl -sSO ${'$'}linuxUrl
                  echo "Downloading Windows CLI: ${'$'}windowsUrl"
                  curl -sSO ${'$'}windowsUrl
                
                  # Clean up temporary files
                  rm -rf LATEST_VERSION
                fi
                ls -la
                
                working_path=%system.teamcity.build.checkoutDir%
                echo ${'$'}working_path
                
                cliFile=${'$'}(ls -1vr *.tar.gz | head -n 1)
                cliFileName=${'$'}(echo "${'$'}cliFile" | cut -c 1-${'$'}((${'$'}{#cliFile}-7)))
                tar -zxvf ${'$'}cliFile
                cd ${'$'}cliFileName
                export PATH="/opt/buildagent/veracode-cli/${'$'}cliFileName:${'$'}PATH"
                cd ${'$'}working_path
                
                PACKAGE_CMD="veracode package --source . --output veracode-artifacts --trust"
                
                if [[ "${'$'}{{ %env.VERACODE_DEBUG% }}" == "true" ]]; then
                  PACKAGE_CMD="${'$'}PACKAGE_CMD --verbose"
                fi
                
                echo "Running: ${'$'}PACKAGE_CMD"
                eval "${'$'}PACKAGE_CMD"
            """.trimIndent()
        }
        step {
            id = "teamcity_veracode_plugin"
            type = "teamcity-veracode-plugin"
            param("deleteIncompleteScan", "1")
            param("teams", "%VERACODE_TEAM%")
            param("appName", "teamcity/%env.TEAMCITY_PROJECT_NAME%")
            param("includenewmodules", "false")
            param("criticality", "VeryHigh")
            param("useGlobalCredentials", "true")
            param("version", "%env.BUILD_NUMBER%")
            param("scanallnonfataltoplevelmodules", "true")
            param("uploadIncludePattern", "veracode-artifacts/**/*.zip")
            param("createProfile", "true")
            param("waitForScan", "true")
            param("createSandbox", "false")
            param("scanTimeOut", "120")
        }
        script {
            name = "Veracode SCA agent-based Scan"
            id = "Veracode_SCA_agent_based_Scan"
            scriptContent = "curl -sSL https://download.sourceclear.com/ci.sh | sh -s -- scan . --recursive --allow-dirty"
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
