#!/usr/bin/env python3
"""Thin SSH wrapper: upload inputs, run lmfiles on the DB host, download outputs.

Each call performs up to three ssh steps:
  1. upload    ssh HOST "mkdir -p JOB && cat > JOB/NAME"   < local file   (per --upload)
  2. execute   ssh HOST "cd JOB && lmfiles ARGS..."                       (the lmfiles args)
  3. download  ssh HOST "cat JOB/NAME"                      > local file   (per --download)
The per-call job directory is removed afterwards unless --keep is given.

Arguments after a literal "--" are passed to lmfiles, and file names in them are
resolved relative to the job directory (the working directory of the remote command).

Example:
  lmfiles-remote.py --host db-host --jump bastion \\
      --upload ./state-input.jsonl=input.jsonl \\
      --download out.jsonl=./out.jsonl \\
      -- --lmcode job.lmcode
"""
import argparse
import os
import re
import shlex
import subprocess
import sys
import uuid

NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def die(msg, code=2):
    print(f"lmfiles-remote: {msg}", file=sys.stderr)
    sys.exit(code)


def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", required=True, help="DB host (ssh destination)")
    p.add_argument("--jump", help="bastion host, passed as ssh -J")
    p.add_argument("--ssh", default="ssh", help="ssh executable (default: ssh)")
    p.add_argument("--ssh-opt", action="append", default=[], metavar="OPT",
                   help="extra ssh -o option, repeatable, e.g. IdentityFile=~/.ssh/lmfiles")
    p.add_argument("--workdir", default="/var/lib/lmfiles/jobs",
                   help="remote parent directory for per-call job directories")
    p.add_argument("--remote-bin", default="lmfiles", help="lmfiles command on the DB host")
    p.add_argument("--upload", action="append", default=[], metavar="LOCAL[=NAME]",
                   help="upload a local file into the job directory (NAME defaults to basename)")
    p.add_argument("--download", action="append", default=[], metavar="NAME=LOCAL",
                   help="download a job-directory file to a local path")
    p.add_argument("--keep", action="store_true", help="keep the remote job directory")
    return p.parse_args(argv)


def main(argv):
    if "--" in argv:
        i = argv.index("--")
        opts, lm_args = argv[:i], argv[i + 1:]
    else:
        opts, lm_args = argv, []
    a = parse_args(opts)
    if not lm_args:
        die("no lmfiles arguments given after --")
    if not a.workdir.startswith("/"):
        die("--workdir must be an absolute remote path")

    uploads = []
    for spec in a.upload:
        local, _, name = spec.partition("=")
        name = name or os.path.basename(local)
        if not NAME_RE.match(name):
            die(f"unsafe remote file name: {name!r}")
        if not os.path.isfile(local):
            die(f"upload source is not a file: {local}")
        uploads.append((local, name))

    downloads = []
    for spec in a.download:
        name, sep, local = spec.partition("=")
        if not sep or not local:
            die(f"--download needs NAME=LOCAL, got {spec!r}")
        if not NAME_RE.match(name):
            die(f"unsafe remote file name: {name!r}")
        downloads.append((name, local))

    base = [a.ssh, "-o", "BatchMode=yes"]
    if a.jump:
        base += ["-J", a.jump]
    for opt in a.ssh_opt:
        base += ["-o", opt]
    base.append(a.host)

    job = f"{a.workdir.rstrip('/')}/job-{uuid.uuid4().hex}"
    q = shlex.quote
    rc = 0
    try:
        # Step 1: create the job directory and upload inputs.
        subprocess.run(base + [f"mkdir -p -- {q(job)}"], check=True)
        for local, name in uploads:
            with open(local, "rb") as src:
                subprocess.run(base + [f"cat > {q(job + '/' + name)}"], stdin=src, check=True)

        # Step 2: run lmfiles inside the job directory; its exit code is ours.
        remote_cmd = f"cd {q(job)} && {q(a.remote_bin)} " + " ".join(q(x) for x in lm_args)
        rc = subprocess.run(base + [remote_cmd]).returncode

        # Step 3: download outputs (attempted even when lmfiles failed).
        for name, local in downloads:
            try:
                with open(local, "wb") as out:
                    subprocess.run(base + [f"cat -- {q(job + '/' + name)}"], stdout=out, check=True)
            except subprocess.CalledProcessError:
                try:
                    os.remove(local)
                except OSError:
                    pass
                print(f"lmfiles-remote: download failed: {name}", file=sys.stderr)
                rc = rc or 1
    except subprocess.CalledProcessError as e:
        print(f"lmfiles-remote: ssh step failed with status {e.returncode}", file=sys.stderr)
        rc = e.returncode or 1
    finally:
        if a.keep:
            print(f"lmfiles-remote: kept remote job directory {job}", file=sys.stderr)
        else:
            subprocess.run(base + [f"rm -rf -- {q(job)}"])
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
