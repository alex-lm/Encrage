FROM python:3.12-slim

LABEL org.opencontainers.image.title="Encrage" \
      org.opencontainers.image.description="Encrage backend: client-side encrypted photo and video cloud (API + admin console)" \
      org.opencontainers.image.source="https://github.com/alex-lm/Encrage" \
      net.unraid.docker.icon="https://raw.githubusercontent.com/alex-lm/Encrage/main/Backend/app/console_static/icon.svg"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN useradd --create-home --uid 10001 encrage

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY manage.py ./manage.py

RUN mkdir -p /data/media && chown -R encrage:encrage /data
USER encrage

ENV ENCRAGE_DATABASE_PATH=/data/encrage.sqlite3 \
    ENCRAGE_MEDIA_ROOT=/data/media \
    ENCRAGE_ENV=production

EXPOSE 8000 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
