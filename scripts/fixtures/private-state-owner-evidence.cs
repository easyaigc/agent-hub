using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Reflection;
using System.Runtime.Serialization.Formatters.Binary;

// Managed-only regression compiled with the actual product C# source. No file,
// ACL, SID, token, handle, process or P/Invoke operation is performed here.
// The serialization stream is constructed in this process, never external input.
internal static class OwnerEvidenceSerializationHarness
{
    private sealed class NonSerializableProbe { }
    private sealed class ReadOnlyDataRefusal : UnauthorizedAccessException
    {
        private readonly IDictionary values = new ReadOnlyDictionary<object, object>(
            new Dictionary<object, object>());
        internal ReadOnlyDataRefusal() : base("WRONG_FILE_OWNER") { }
        public override IDictionary Data { get { return values; } }
    }

    private static void Require(bool value)
    {
        if (!value) throw new InvalidOperationException();
    }

    private static int Main(string[] args)
    {
        string dataPolicy = "not-checked";
        try
        {
            if (args.Length != 0) throw new ArgumentException();
            try
            {
                new Exception().Data["probe"] = new NonSerializableProbe();
                dataPolicy = "accepts-nonserializable";
            }
            catch (ArgumentException) { dataPolicy = "rejects-nonserializable"; }

            Type security = typeof(CodexManagePrivateStateSecurity);
            BindingFlags hidden = BindingFlags.Static | BindingFlags.NonPublic;
            MethodInfo attach = security.GetMethod("AttachOwnerEvidence", hidden);
            Type evidenceType = security.GetNestedType("OwnerEvidence", BindingFlags.NonPublic);
            string frame = "OWNER2:check|-|-|-|-|-|-|-|-|unknown|none|none";
            var error = new UnauthorizedAccessException("WRONG_FILE_OWNER");
            error.Data["arbitrary"] = "fixture-not-exported";
            if (attach == null)
            {
                // Also run against the frozen old source: Framework rejects the
                // assignment; runtimes allowing it must still fail round-trip.
                object value = Activator.CreateInstance(evidenceType,
                    BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public,
                    null, new object[] { frame }, null);
                error.Data["CodexManage.OwnerEvidence"] = value;
            }
            else attach.Invoke(null, new object[] { error, frame });

            string expected = "UNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException:owner-check:" + frame;
            MethodInfo format = typeof(PrivateState).GetMethod("FailureResponse", hidden);
            Require((string)format.Invoke(null, new object[] { error }) == expected);
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(error) == frame);

            // This tests the Framework storage requirement behaviorally even
            // where Exception.Data itself no longer checks Type.IsSerializable.
            Exception restored;
            using (var stream = new MemoryStream())
            {
                var formatter = new BinaryFormatter();
                formatter.Serialize(stream, error);
                stream.Position = 0;
                restored = (Exception)formatter.Deserialize(stream);
            }
            Require((string)format.Invoke(null, new object[] { restored }) == expected);
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(restored) == frame);

            var arbitrary = new UnauthorizedAccessException("WRONG_FILE_OWNER");
            arbitrary.Data["CodexManage.OwnerEvidence"] = "fixture-not-exported";
            Require((string)format.Invoke(null, new object[] { arbitrary }) ==
                "UNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException:owner-check");
            Require(attach != null);
            var readOnly = new ReadOnlyDataRefusal();
            attach.Invoke(null, new object[] { readOnly, frame });
            Require(readOnly.Message == "WRONG_FILE_OWNER");
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(readOnly) == null);
            Console.WriteLine("{\"kind\":\"managed-owner-evidence\",\"passed\":true," +
                "\"exceptionDataPolicy\":\"" + dataPolicy + "\",\"frameRoundTrip\":true," +
                "\"arbitraryDataExcluded\":true,\"attachmentFailurePreservesRefusal\":true," +
                "\"windowsPInvokeExecuted\":false}");
            return 0;
        }
        catch (Exception error)
        {
            while (error is TargetInvocationException && error.InnerException != null)
                error = error.InnerException;
            Console.WriteLine("{\"kind\":\"managed-owner-evidence\",\"passed\":false," +
                "\"exceptionDataPolicy\":\"" + dataPolicy + "\",\"failureType\":\"" +
                error.GetType().Name + "\",\"windowsPInvokeExecuted\":false}");
            return 1;
        }
    }
}
