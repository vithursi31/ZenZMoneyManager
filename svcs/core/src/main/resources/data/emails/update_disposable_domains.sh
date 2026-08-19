#!/bin/bash

ORIGINAL_FILE="disposable_domains.txt"
TEMP_DIR=$(mktemp -d)

# URLs - all plain text format, one domain per line
URLS=(
    "https://raw.githubusercontent.com/disposable/disposable-email-domains/refs/heads/master/domains.txt"
    "https://raw.githubusercontent.com/disposable-email-domains/disposable-email-domains/refs/heads/main/disposable_email_blocklist.conf"
    "https://raw.githubusercontent.com/martenson/disposable-email-domains/master/disposable_email_blocklist.conf"
    "https://raw.githubusercontent.com/FGRibreau/mailchecker/master/list.txt"
    "https://raw.githubusercontent.com/andreis/disposable/master/blacklist.txt"
)

echo "Downloading disposable domain lists from ${#URLS[@]} sources..."

# Download all files
for i in "${!URLS[@]}"; do
    echo "Downloading source $((i+1))/${#URLS[@]}..."
    wget -q -O "$TEMP_DIR/list_$i.txt" "${URLS[$i]}" || echo "Warning: Failed to download source $((i+1))"
done

echo "Processing files..."

# Combine all downloaded files with original
if [ -f "$ORIGINAL_FILE" ]; then
    cat "$ORIGINAL_FILE" "$TEMP_DIR"/*.txt > "$TEMP_DIR/combined.txt"
else
    cat "$TEMP_DIR"/*.txt > "$TEMP_DIR/combined.txt"
fi

# Sort, remove duplicates and empty lines
sort "$TEMP_DIR/combined.txt" | uniq | sed '/^$/d' > "$ORIGINAL_FILE"

# Clean up
rm -rf "$TEMP_DIR"

echo "Done! Updated $ORIGINAL_FILE with $(wc -l < "$ORIGINAL_FILE") unique disposable domains."
