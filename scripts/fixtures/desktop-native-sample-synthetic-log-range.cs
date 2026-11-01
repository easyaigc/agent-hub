using System;
using System.Globalization;
using System.IO;
using System.Text;

// Owned synthetic NTFS fixtures only. This entry point deliberately has no PID,
// token or PFN proof and must never be installed as a product helper. The real
// trace-state.cs/trace-process.cs are compiled unchanged alongside this Main.
internal static class SyntheticNativeSampleLogRange
{
    public static int Main(string[] args)
    {
        try
        {
            if (args.Length != 5) throw new ArgumentException();
            long offset = Int64.Parse(args[2], CultureInfo.InvariantCulture);
            int count = Int32.Parse(args[3], CultureInfo.InvariantCulture);
            using (var range = new TraceState.LogRangeFile(args[0], args[1], count == 0, verifiedDesktop: null))
            {
                // Snapshot contains synthetic log bytes. Keep it off stdout and
                // in the caller's pre-created private file, never tool output.
                File.WriteAllText(args[4], range.Snapshot(offset, count), new UTF8Encoding(false));
            }
            Console.WriteLine("SYNTHETIC_NTFS_RANGE_ONLY");
            return 0;
        }
        catch
        {
            Console.Error.WriteLine("SYNTHETIC_NTFS_RANGE_REJECTED");
            return 1;
        }
    }
}
