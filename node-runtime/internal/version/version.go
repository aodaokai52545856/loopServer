package version

type Info struct {
	Component string `json:"component"`
	Version   string `json:"version"`
	Protocol  string `json:"protocol"`
}

func Current() Info {
	return Info{Component: "node-runtime", Version: "dev", Protocol: "v1"}
}
