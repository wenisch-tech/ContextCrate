{{- define "harvex.name" -}}harvex{{- end }}
{{- define "harvex.fullname" -}}{{ .Release.Name }}-harvex{{- end }}
{{- define "harvex.labels" -}}
app.kubernetes.io/name: {{ include "harvex.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}
