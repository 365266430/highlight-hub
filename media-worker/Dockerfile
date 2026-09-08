FROM python:3.12-slim
WORKDIR /worker
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl fontconfig \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# bundled open font for captions (OFL licensed)
RUN mkdir -p /usr/share/fonts/opentype/hub
COPY app ./app
RUN useradd -r -u 10002 hub && mkdir -p /data && chown -R hub:hub /worker /data
USER hub
ENV STORAGE_ROOT=/data FFMPEG_PATH=/usr/bin/ffmpeg FFPROBE_PATH=/usr/bin/ffprobe
EXPOSE 8123
HEALTHCHECK --interval=30s --timeout=5s CMD curl -f http://127.0.0.1:8123/health || exit 1
CMD ["python", "-m", "app"]
