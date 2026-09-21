{{/*
The chart's names, labels and the pieces the Deployment and the migration hook share.
No helm.sh/chart or app.kubernetes.io/version label, on purpose: the committed rendering under
deploy/kubernetes/ must not change with every release, and the image tag says which version runs.
*/}}

{{- define "tesseraql.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "tesseraql.fullname" -}}
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

{{- define "tesseraql.labels" -}}
app.kubernetes.io/name: {{ include "tesseraql.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: tesseraql
{{- end }}

{{- define "tesseraql.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tesseraql.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "tesseraql.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "tesseraql.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
The image: the deployment's derived image, which the chart cannot guess.
*/}}
{{- define "tesseraql.image" -}}
{{- $repository := required "image.repository is the deployment's derived image (deploy/Dockerfile: the official runtime image with your packages unpacked under /stack). The runtime image alone hosts no application, so the chart has no default for it." .Values.image.repository -}}
{{- printf "%s:%s" $repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end }}

{{/*
The environment every container of the stack gets: the pod's name as the node's name, the drain
bound, the spool store, the heap share, the profile, the secrets directory, then the values.
*/}}
{{- define "tesseraql.env" -}}
- name: TESSERAQL_NODE_ID
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
- name: TESSERAQL_SHUTDOWN_TIMEOUT
  value: {{ printf "%ds" (int .Values.shutdownTimeoutSeconds) | quote }}
- name: TESSERAQL_TEMP_STORE
  value: {{ .Values.tempStore | quote }}
{{- if .Values.javaToolOptions }}
- name: JAVA_TOOL_OPTIONS
  value: {{ .Values.javaToolOptions | quote }}
{{- end }}
{{- if .Values.profile }}
- name: TESSERAQL_ENV
  value: {{ .Values.profile | quote }}
{{- end }}
{{- if .Values.secretsMount.secretName }}
- name: TESSERAQL_SECRETS_DIR
  value: {{ .Values.secretsMount.mountPath | quote }}
{{- end }}
{{- with .Values.env }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{- define "tesseraql.envFrom" -}}
{{- with .Values.envFrom.secretRefs -}}
envFrom:
  {{- range . }}
  - secretRef:
      name: {{ . | quote }}
  {{- end }}
{{- end }}
{{- end }}

{{/*
The stack file over the baked directory, and the secrets as files.
*/}}
{{- define "tesseraql.volumeMounts" -}}
{{- if or .Values.stackFile .Values.secretsMount.secretName -}}
volumeMounts:
  {{- if .Values.stackFile }}
  - name: stack-file
    mountPath: /stack/tesseraql-stack.yml
    subPath: tesseraql-stack.yml
    readOnly: true
  {{- end }}
  {{- if .Values.secretsMount.secretName }}
  - name: secrets
    mountPath: {{ .Values.secretsMount.mountPath }}
    readOnly: true
  {{- end }}
{{- end }}
{{- end }}

{{- define "tesseraql.volumes" -}}
{{- if or .Values.stackFile .Values.secretsMount.secretName -}}
volumes:
  {{- if .Values.stackFile }}
  - name: stack-file
    configMap:
      name: {{ include "tesseraql.fullname" . }}-stack
  {{- end }}
  {{- if .Values.secretsMount.secretName }}
  - name: secrets
    secret:
      secretName: {{ .Values.secretsMount.secretName }}
  {{- end }}
{{- end }}
{{- end }}
