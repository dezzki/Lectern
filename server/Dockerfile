FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
# libarchive is used to read .cbr (RAR) comic archives.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libarchive13 \
 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .

# The read-only papers mount contains files owned by the host group
# "gigatonshare" (gid 1001, mode rw-rw----). Run the container's unprivileged
# user with that GID so it can read the library without any chmod/write access.
# All library files seen on GIGATON are either world-readable or group-1001
# readable, so this is sufficient and keeps the mount read-only.
RUN groupadd -g 1001 appgroup \
 && useradd --system --uid 10001 --gid appgroup --shell /usr/sbin/nologin appuser \
 && mkdir -p /data \
 && chown -R appuser:appgroup /app /data

USER appuser
EXPOSE 8080
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]