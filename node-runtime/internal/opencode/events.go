package opencode

import (
	"encoding/json"
	"strings"
)

// ProtocolErrorCode is returned when OpenCode stdout is not a valid event stream.
const ProtocolErrorCode = "OPENCODE_PROTOCOL_ERROR"

const (
	eventAgentStarted  = "agent.started"
	eventAgentMessage  = "agent.message"
	eventToolStarted   = "tool.started"
	eventToolFinished  = "tool.finished"
	eventAgentFinished = "agent.finished"
	eventAgentRaw      = "agent.raw"
)

var sensitivePayloadKeys = map[string]struct{}{
	"token":            {},
	"password":         {},
	"secret":           {},
	"authorization":    {},
	"api_key":          {},
	"apikey":           {},
	"access_token":     {},
	"refresh_token":    {},
	"gitlab_token":     {},
	"ci_job_token":     {},
	"anthropic_api_key": {},
	"openai_api_key":   {},
}

type upstreamEvent struct {
	Type      string         `json:"type"`
	SessionID string         `json:"sessionID"`
	Part      map[string]any `json:"part"`
	Error     map[string]any `json:"error"`
	raw       map[string]any
}

func parseUpstream(raw json.RawMessage) (upstreamEvent, error) {
	var envelope map[string]any
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return upstreamEvent{}, err
	}
	ev := upstreamEvent{raw: envelope}
	if v, ok := envelope["type"].(string); ok {
		ev.Type = v
	}
	if v, ok := envelope["sessionID"].(string); ok {
		ev.SessionID = v
	}
	if part, ok := envelope["part"].(map[string]any); ok {
		ev.Part = part
	}
	if errObj, ok := envelope["error"].(map[string]any); ok {
		ev.Error = errObj
	}
	return ev, nil
}

func mapUpstream(ev upstreamEvent) []mappedEvent {
	switch ev.Type {
	case "step_start":
		return []mappedEvent{{Type: eventAgentStarted, Payload: safePayload(ev.raw), SessionID: ev.SessionID}}
	case "text":
		payload := safePayload(ev.raw)
		if ev.Part != nil {
			if text, ok := ev.Part["text"].(string); ok {
				payload["text"] = text
			}
		}
		return []mappedEvent{{Type: eventAgentMessage, Payload: payload, SessionID: ev.SessionID}}
	case "tool_start":
		return []mappedEvent{{Type: eventToolStarted, Payload: toolPayload(ev), SessionID: ev.SessionID}}
	case "tool_finish":
		return []mappedEvent{{Type: eventToolFinished, Payload: toolPayload(ev), SessionID: ev.SessionID}}
	case "tool_use":
		status := toolStatus(ev)
		started := mappedEvent{Type: eventToolStarted, Payload: toolPayload(ev), SessionID: ev.SessionID}
		finished := mappedEvent{Type: eventToolFinished, Payload: toolPayload(ev), SessionID: ev.SessionID}
		switch status {
		case "pending", "running":
			return []mappedEvent{started}
		case "completed", "":
			// OpenCode CLI JSON commonly emits only the completed tool_use.
			return []mappedEvent{started, finished}
		default:
			return []mappedEvent{started, finished}
		}
	case "step_finish":
		return []mappedEvent{{Type: eventAgentFinished, Payload: safePayload(ev.raw), SessionID: ev.SessionID}}
	case "error":
		payload := safePayload(ev.raw)
		return []mappedEvent{{Type: eventAgentFinished, Payload: payload, SessionID: ev.SessionID}}
	default:
		if strings.TrimSpace(ev.Type) == "" {
			return nil
		}
		return []mappedEvent{{Type: eventAgentRaw, Payload: safePayload(ev.raw), SessionID: ev.SessionID}}
	}
}

type mappedEvent struct {
	Type      string
	Payload   map[string]any
	SessionID string
}

func toolStatus(ev upstreamEvent) string {
	if ev.Part == nil {
		return ""
	}
	state, _ := ev.Part["state"].(map[string]any)
	if state == nil {
		return ""
	}
	status, _ := state["status"].(string)
	return status
}

func toolPayload(ev upstreamEvent) map[string]any {
	payload := safePayload(ev.raw)
	if ev.Part != nil {
		if tool, ok := ev.Part["tool"].(string); ok {
			payload["tool"] = tool
		}
		if callID, ok := ev.Part["callID"].(string); ok {
			payload["callID"] = callID
		}
	}
	return payload
}

func safePayload(raw map[string]any) map[string]any {
	if raw == nil {
		return map[string]any{}
	}
	out := make(map[string]any, len(raw))
	for key, value := range raw {
		if isSensitiveKey(key) {
			continue
		}
		out[key] = scrubValue(value)
	}
	return out
}

func scrubValue(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		return safePayload(typed)
	case []any:
		out := make([]any, len(typed))
		for i, item := range typed {
			out[i] = scrubValue(item)
		}
		return out
	default:
		return value
	}
}

func isSensitiveKey(key string) bool {
	normalized := strings.ToLower(strings.ReplaceAll(key, "-", "_"))
	if _, ok := sensitivePayloadKeys[normalized]; ok {
		return true
	}
	if strings.Contains(normalized, "token") || strings.Contains(normalized, "password") || strings.Contains(normalized, "secret") {
		return true
	}
	return false
}
