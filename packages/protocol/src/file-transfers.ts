/** Computer file delivery is independent of Agent tasks and chat attachments. */
export const FILE_TRANSFER_MAX_BYTES = 20 * 1024 * 1024;
export interface FileTransferManifest {
  hostId: string;
  id: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
}
export interface FileTransferReceipt extends FileTransferManifest {
  phase: 'prepared' | 'completed';
  destinationPath: string;
  createdAt: string;
}
export interface FileTransferCatalog {
  hostId: string;
  destinationDirectory: string;
  maxFileBytes: number;
  files: FileTransferReceipt[];
}
export function parseFileTransferManifest(input: unknown): FileTransferManifest {
  const value = input as Record<string, unknown>;
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).sort().join(',') !== 'hostId,id,mediaType,name,sha256,sizeBytes' ||
    typeof value.hostId !== 'string' ||
    !value.hostId.trim() ||
    typeof value.id !== 'string' ||
    !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value.id) ||
    typeof value.name !== 'string' ||
    !value.name.trim() ||
    [...value.name].reduce(
      (size, char) =>
        size +
        (char.codePointAt(0)! > 0xffff
          ? 4
          : char.charCodeAt(0) > 0x7ff
            ? 3
            : char.charCodeAt(0) > 0x7f
              ? 2
              : 1),
      0,
    ) > 180 ||
    [...value.name].some((char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) ||
    /[/\\:<>"|?*]/.test(value.name) ||
    /[. ]$/.test(value.name) ||
    /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)/i.test(value.name) ||
    typeof value.mediaType !== 'string' ||
    !/^[\w.+-]+\/[\w.+-]+$/.test(value.mediaType) ||
    typeof value.sizeBytes !== 'number' ||
    !Number.isSafeInteger(value.sizeBytes) ||
    value.sizeBytes < 1 ||
    value.sizeBytes > FILE_TRANSFER_MAX_BYTES ||
    typeof value.sha256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(value.sha256)
  )
    throw new TypeError('无效文件清单；文件须非空且不超过 20 MB，名称不能包含路径或系统保留字符');
  return value as unknown as FileTransferManifest;
}
