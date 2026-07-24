{{- define "contextcrate.name" -}}contextcrate{{- end }}
{{- define "contextcrate.fullname" -}}{{ .Release.Name }}-contextcrate{{- end }}
{{- define "contextcrate.labels" -}}
app.kubernetes.io/name: {{ include "contextcrate.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}
