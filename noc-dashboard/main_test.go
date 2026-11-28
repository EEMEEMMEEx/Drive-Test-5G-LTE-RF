package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestResolveDir(t *testing.T) {
	dir, err := resolveDir("")
	if err != nil {
		t.Fatalf("resolveDir(\"\") unexpected error: %v", err)
	}
	if dir != "." {
		t.Fatalf("expected '.', got %q", dir)
	}

	if _, err := resolveDir("non-existent-dir-xyz"); err == nil {
		t.Fatal("expected error for missing directory, got nil")
	}
}

func TestHandlerServesStaticAndBlocksSensitiveFiles(t *testing.T) {
	h := newHandler(".")

	cases := []struct {
		name       string
		method     string
		path       string
		wantStatus int
		wantSubstr string
	}{
		{"index", http.MethodGet, "/", http.StatusOK, "NOC REAL-TIME FLEET TELEMETRY CONSOLE"},
		{"app_js", http.MethodGet, "/app.js", http.StatusOK, "COLORS"},
		{"env_js", http.MethodGet, "/env.js", http.StatusOK, "window.ENV"},
		{"block_dotenv", http.MethodGet, "/.env", http.StatusNotFound, ""},
		{"block_nested_dotenv", http.MethodGet, "/sub/.env", http.StatusNotFound, ""},
		{"block_go_src", http.MethodGet, "/main.go", http.StatusNotFound, ""},
		{"block_go_mod", http.MethodGet, "/go.mod", http.StatusNotFound, ""},
		{"method_post", http.MethodPost, "/", http.StatusMethodNotAllowed, ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, tc.path, nil)
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)

			if rec.Code != tc.wantStatus {
				t.Fatalf("%s %s: status=%d, want %d", tc.method, tc.path, rec.Code, tc.wantStatus)
			}
			if tc.wantStatus == http.StatusOK {
				if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
					t.Fatalf("Cache-Control=%q, want no-store", cc)
				}
			}
			if tc.wantSubstr != "" && !strings.Contains(rec.Body.String(), tc.wantSubstr) {
				t.Fatalf("body for %s missing %q", tc.path, tc.wantSubstr)
			}
		})
	}
}
