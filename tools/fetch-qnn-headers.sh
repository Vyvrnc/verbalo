#!/usr/bin/env bash
# Fetches the QNN C API headers needed by the NPU probe into $1 (default /work/qnn-include).
#
# Why fetched, not vendored: the headers carry a "Confidential and Proprietary - Qualcomm"
# notice even in Qualcomm's public BSD-3 repo, so we do not redistribute them in this repo.
# Source: github.com/qualcomm/geniex-qairt-plugin (qnn-api/include, QNN SDK v2.36.1 API,
# interface v2.27), pinned to a commit. QNN keeps the v2 API backward compatible, so these
# headers work with the newer qnn-runtime 2.51 libraries bundled in the APK.
# Runs inside the build container. If it fails, the app still builds; the QNN part of the
# NPU test then reports "QNN headers not available at build time".
set -euo pipefail
DEST="${1:-/work/qnn-include}"
COMMIT=15d6e053a62d88b6f7b72c1d5eb9706b299d3012
BASE="https://raw.githubusercontent.com/qualcomm/geniex-qairt-plugin/$COMMIT/qnn-api/include"
FILES=(QnnBackend.h QnnCommon.h QnnContext.h QnnDevice.h QnnError.h QnnGlobalConfig.h
       QnnGraph.h QnnInterface.h QnnLog.h QnnMem.h QnnOpDef.h QnnOpPackage.h QnnProfile.h
       QnnProperty.h QnnSdkBuildId.h QnnSignal.h QnnTensor.h QnnTypes.h
       HTP/QnnHtpDevice.h HTP/QnnHtpPerfInfrastructure.h HTP/QnnHtpCommon.h
       System/QnnSystemCommon.h System/QnnSystemContext.h System/QnnSystemDlc.h
       System/QnnSystemInterface.h System/QnnSystemLog.h System/QnnSystemTensor.h)
# Bump when FILES changes, so an existing header dir is fetched again.
SET=2

if [[ -f "$DEST/.complete-$COMMIT-$SET" ]]; then exit 0; fi
rm -rf "$DEST.tmp" && mkdir -p "$DEST.tmp/HTP" "$DEST.tmp/System"
for f in "${FILES[@]}"; do
    curl -fsSL --retry 3 -o "$DEST.tmp/$f" "$BASE/$f"
done
rm -rf "$DEST" && mv "$DEST.tmp" "$DEST" && touch "$DEST/.complete-$COMMIT-$SET"
echo ">> QNN headers ($COMMIT) in $DEST"
