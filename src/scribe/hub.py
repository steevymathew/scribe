"""Model downloads from the Hugging Face Hub (the one permitted network step).

Shared by the ONNX backend and the Silero VAD so both use the same
announce-once download UX under the console noise policy (ROADMAP §5).
Everything here is one-time setup; after the files are cached Scribe runs
fully offline.
"""

import logging

log = logging.getLogger(__name__)


def hf_fetch(repo, fname):
    """hf_hub_download with a one-line console notice on first (real) download.

    Progress bars are suppressed by the noise policy, so without this a
    gigabyte-sized first download would look like a hang. A cheap HEAD checks
    the file exists before announcing, so optional files (sidecars that only
    big models have) can 404 without a misleading "Downloading..." line.
    """
    from huggingface_hub import get_hf_file_metadata, hf_hub_download, hf_hub_url
    try:
        return hf_hub_download(repo, fname, local_files_only=True)  # cached?
    except Exception:
        get_hf_file_metadata(hf_hub_url(repo, fname))  # 404s fast if absent
        print(f"  Downloading {fname.rsplit('/', 1)[-1]} (one-time)...", flush=True)
        log.info("downloading %s/%s", repo, fname)
        return hf_hub_download(repo, fname)
