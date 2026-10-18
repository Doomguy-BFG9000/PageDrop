FROM python:3.12-slim-bookworm

# Install Calibre (includes ebook-convert, ebook-meta)
RUN apt-get update && apt-get install -y --no-install-recommends \
    calibre \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /worker

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY poll.py safe_fetch.py .

CMD ["python", "poll.py"]
