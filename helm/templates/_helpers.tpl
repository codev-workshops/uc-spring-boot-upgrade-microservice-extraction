{{/*
Chart name.
*/}}
{{- define "realworld.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name.
*/}}
{{- define "realworld.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "realworld.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "realworld.labels" -}}
helm.sh/chart: {{ include "realworld.chart" . }}
app.kubernetes.io/part-of: {{ include "realworld.name" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Per-component names, labels and selector labels. Call with (dict "root" $ "component" "backend").
*/}}
{{- define "realworld.componentName" -}}
{{- printf "%s-%s" (include "realworld.fullname" .root) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "realworld.selectorLabels" -}}
app.kubernetes.io/name: {{ include "realworld.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "realworld.componentLabels" -}}
{{ include "realworld.labels" .root }}
{{ include "realworld.selectorLabels" . }}
{{- end }}

{{- define "realworld.image" -}}
{{- $tag := default .root.Chart.AppVersion .image.tag -}}
{{- if .image.registry -}}
{{- printf "%s/%s:%s" .image.registry .image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end -}}
{{- end }}

{{- define "realworld.backend.fullname" -}}
{{- include "realworld.componentName" (dict "root" . "component" "backend") }}
{{- end }}

{{- define "realworld.frontend.fullname" -}}
{{- include "realworld.componentName" (dict "root" . "component" "frontend") }}
{{- end }}

{{- define "realworld.backend.secretName" -}}
{{- default (include "realworld.backend.fullname" .) .Values.backend.secret.existingSecret }}
{{- end }}

{{- define "realworld.backend.pvcName" -}}
{{- default (printf "%s-data" (include "realworld.backend.fullname" .)) .Values.backend.persistence.existingClaim }}
{{- end }}

{{/*
A SQLite file on a ReadWriteOnce volume can only be used by one backend pod.
*/}}
{{- define "realworld.backend.validate" -}}
{{- include "realworld.postgresql.validate" . }}
{{- if .Values.backend.persistence.enabled }}
{{- if and .Values.backend.hpa.enabled (gt (int .Values.backend.hpa.maxReplicas) 1) }}
{{- fail "backend.persistence.enabled (SQLite on a ReadWriteOnce PVC) cannot be combined with backend.hpa.enabled and maxReplicas > 1: use an external database (see values-prod.yaml) or disable the HPA." }}
{{- end }}
{{- if and (not .Values.backend.hpa.enabled) (gt (int .Values.backend.replicaCount) 1) }}
{{- fail "backend.persistence.enabled (SQLite on a ReadWriteOnce PVC) requires backend.replicaCount: 1." }}
{{- end }}
{{- end }}
{{- end }}

{{- define "realworld.postgresql.fullname" -}}
{{- include "realworld.componentName" (dict "root" . "component" "postgresql") }}
{{- end }}

{{- define "realworld.postgresql.secretName" -}}
{{- default (include "realworld.postgresql.fullname" .) .Values.postgresql.auth.existingSecret }}
{{- end }}

{{- define "realworld.postgresql.jdbcUrl" -}}
{{- printf "jdbc:postgresql://%s:%v/%s" (include "realworld.postgresql.fullname" .) .Values.postgresql.port .Values.postgresql.database }}
{{- end }}

{{- define "realworld.postgresql.validate" -}}
{{- if .Values.postgresql.enabled }}
{{- if .Values.backend.persistence.enabled }}
{{- fail "postgresql.enabled replaces SQLite: set backend.persistence.enabled: false (see values-postgresql.yaml)." }}
{{- end }}
{{- if not (has "prod" (splitList "," (toString .Values.backend.config.SPRING_PROFILES_ACTIVE | nospace))) }}
{{- fail "postgresql.enabled requires backend.config.SPRING_PROFILES_ACTIVE to include `prod` (the default profile loads SQLite-only seed data)." }}
{{- end }}
{{- end }}
{{- end }}
