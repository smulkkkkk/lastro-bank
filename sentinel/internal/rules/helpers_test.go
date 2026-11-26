package rules

import (
	"bytes"
	"io"
)

func bytesReader(body []byte) io.Reader { return bytes.NewReader(body) }
