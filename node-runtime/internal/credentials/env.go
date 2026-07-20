package credentials

// AgentEnvAllowlist is the only environment keys permitted for OpenCode.
var AgentEnvAllowlist = map[string]bool{
	"PATH": true, "HOME": true, "USERPROFILE": true, "TMPDIR": true, "TEMP": true,
	"LANG": true, "LC_ALL": true, "JAVA_HOME": true, "MAVEN_HOME": true,
	"NODE_HOME": true, "OPENCODE_CONFIG_DIR": true, "OPENCODE_CONFIG_CONTENT": true,
	"ANTHROPIC_API_KEY": true, "OPENAI_API_KEY": true, "GOOGLE_GENERATIVE_AI_API_KEY": true,
}

// FilterAgentEnv copies only allowlisted keys from source.
// CI_*, GITLAB_*, GIT_ASKPASS, proxy passwords and task tokens are never included.
func FilterAgentEnv(source map[string]string) map[string]string {
	out := make(map[string]string)
	if source == nil {
		return out
	}
	for key, value := range source {
		if AgentEnvAllowlist[key] {
			out[key] = value
		}
	}
	return out
}

// FilterAgentEnvList filters KEY=VALUE entries the same way as FilterAgentEnv.
func FilterAgentEnvList(environ []string) map[string]string {
	source := make(map[string]string, len(environ))
	for _, entry := range environ {
		key, value, ok := splitEnv(entry)
		if !ok {
			continue
		}
		source[key] = value
	}
	return FilterAgentEnv(source)
}

func splitEnv(entry string) (key, value string, ok bool) {
	for i := 0; i < len(entry); i++ {
		if entry[i] == '=' {
			return entry[:i], entry[i+1:], true
		}
	}
	return "", "", false
}
