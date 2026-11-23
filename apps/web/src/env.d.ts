// File System Access API pieces that TypeScript's DOM lib does not ship yet (Chrome/Edge only).
interface SaveFilePickerOptions {
  suggestedName?: string;
  types?: { description?: string; accept: Record<string, string[]> }[];
}

interface Window {
  showSaveFilePicker?: (options?: SaveFilePickerOptions) => Promise<FileSystemFileHandle>;
}
