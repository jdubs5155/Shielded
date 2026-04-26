# DOLPHIN 3.0 GGUF MODEL

The Dolphin3.0-Llama3.1-8B-Q4_K_M.gguf model file should be downloaded from:
https://huggingface.co/bartowski/Dolphin3.0-Llama3.1-8B-GGUF

Download the Q4_K_M quantized version and place it in:
app/src/main/assets/Dolphin3.0-Llama3.1-8B-Q4_K_M.gguf

This file is required for the Dolphin 3.0 query refinement feature to work properly.
Without it, the app will continue to function using the standard NLP engine without refinement.
