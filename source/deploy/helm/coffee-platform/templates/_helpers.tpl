{{- define "coffee.namespace" -}}{{ .Values.namespaceOverride | default .Release.Namespace }}{{- end }}
{{- define "coffee.labels" -}}
app.kubernetes.io/part-of: coffee-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "coffee.storageClass" -}}
{{- if .Values.storageClassName }}
storageClassName: {{ .Values.storageClassName | quote }}
{{- end }}
{{- end }}

