FROM python:3.12-slim-bookworm

# Install Calibre (includes ebook-convert, ebook-meta; pulls in poppler-utils for pdftotext)
RUN apt-get update && apt-get install -y --no-install-recommends \
    calibre \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged user that every Calibre / pdftotext child runs as (see sandbox.py). The
# worker process stays root only so it can drop privileges for those children: the
# service key lives in the worker's environment (/proc/1/environ), which only root or
# the worker's own uid can read.
RUN groupadd --system convert \
    && useradd --system --gid convert --no-create-home --home-dir /nonexistent \
       --shell /usr/sbin/nologin convert

WORKDIR /worker

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY poll.py safe_fetch.py sandbox.py ./

# Refuse to run conversions if the worker cannot switch to the sandbox user.
ENV SANDBOX_USER=convert \
    SANDBOX_REQUIRED=1 \
    PYTHONUNBUFFERED=1

CMD ["python", "poll.py"]
