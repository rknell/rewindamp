FROM python:3.12-slim
ARG APP_UID=1000
ARG APP_GID=1000
RUN groupadd -g ${APP_GID} rewindamp && useradd -u ${APP_UID} -g rewindamp -M rewindamp \
    && mkdir /config /podcasts && chown rewindamp:rewindamp /config /podcasts
WORKDIR /app
COPY server.py ./
COPY static ./static
USER rewindamp
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=3).read()"
CMD ["python", "server.py"]
