package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
)

const dashboardVersion = "v1.15.0"

func main() {
	defaultPort := os.Getenv("PORT")
	if defaultPort == "" {
		defaultPort = "3000"
	}

	host := flag.String("host", "127.0.0.1", "Host address to bind")
	port := flag.String("port", defaultPort, "Port to listen on")
	dir := flag.String("dir", "", "Directory to serve (auto-detected if empty)")
	open := flag.Bool("open", false, "Open dashboard URL in default browser")
	flag.Parse()

	rootDir, err := resolveDir(*dir)
	if err != nil {
		log.Fatalf("resolve web root: %v", err)
	}

	addr := net.JoinHostPort(*host, *port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("listen %s: %v", addr, err)
	}

	srv := &http.Server{
		Handler:           newHandler(rootDir),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	urlHost := *host
	if urlHost == "0.0.0.0" || urlHost == "::" || urlHost == "" {
		urlHost = "127.0.0.1"
	}
	dashboardURL := fmt.Sprintf("http://%s", net.JoinHostPort(urlHost, *port))
	log.Printf("NOC Dashboard %s serving %s at %s", dashboardVersion, rootDir, dashboardURL)

	if *open {
		if err := openBrowser(dashboardURL); err != nil {
			log.Printf("warning: failed to open browser: %v", err)
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Printf("shutdown error: %v", err)
		}
	}()

	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("server error: %v", err)
	}
}

// resolveDir picks the static dashboard directory or validates an explicit override.
func resolveDir(explicit string) (string, error) {
	if explicit != "" {
		info, err := os.Stat(explicit)
		if err != nil {
			return "", err
		}
		if !info.IsDir() {
			return "", fmt.Errorf("%s is not a directory", explicit)
		}
		return filepath.Clean(explicit), nil
	}

	for _, candidate := range []string{".", "noc-dashboard"} {
		if info, err := os.Stat(filepath.Join(candidate, "index.html")); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	return "", errors.New("could not find index.html in '.' or 'noc-dashboard'")
}

// isBlockedPath prevents exposing dotfiles (.env) and Go source/module files over HTTP.
func isBlockedPath(rawPath string) bool {
	cleaned := path.Clean("/" + rawPath)
	for _, seg := range strings.Split(cleaned, "/") {
		if strings.HasPrefix(seg, ".") && seg != "." {
			return true
		}
	}
	base := path.Base(cleaned)
	if base == "go.mod" || base == "go.sum" {
		return true
	}
	return strings.EqualFold(path.Ext(base), ".go")
}

// newHandler creates the local static file handler with dev cache headers and file filtering.
func newHandler(rootDir string) http.Handler {
	fileServer := http.FileServer(http.Dir(rootDir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if isBlockedPath(r.URL.Path) {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		fileServer.ServeHTTP(w, r)
	})
}

func openBrowser(targetURL string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", targetURL)
	case "darwin":
		cmd = exec.Command("open", targetURL)
	default:
		cmd = exec.Command("xdg-open", targetURL)
	}
	return cmd.Start()
}
