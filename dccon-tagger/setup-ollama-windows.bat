@echo off
rem DCCon Tagger - one-time Ollama setup for Windows.
rem Run after installing Ollama from https://ollama.com/download

rem Let the Tampermonkey userscript in Firefox talk to Ollama.
setx OLLAMA_ORIGINS "*"

rem Download the vision model used for tagging (about 6 GB).
ollama pull qwen2.5vl:7b

echo.
echo Done. Quit Ollama from the system tray and start it again so the new setting applies.
echo Optional, more accurate but slower:  ollama pull qwen2.5vl:32b
pause
