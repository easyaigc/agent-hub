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
    // A fault double for the Framework missing-key lookup boundary on any CLR.
    // The original real ReadOnlyDictionary case above remains unchanged.
    private sealed class MissingKeyData : Hashtable
    {
        public override object this[object key]
        {
            get { throw new KeyNotFoundException(); }
            set { throw new NotSupportedException(); }
        }
    }
    private sealed class FailedLookupData : Hashtable
    {
        private readonly bool failContains;
        internal FailedLookupData(bool contains) { failContains = contains; }
        public override bool Contains(object key)
        {
            if (failContains) throw new InvalidOperationException();
            return true;
        }
        public override object this[object key]
        {
            get { throw new InvalidOperationException(); }
            set { throw new NotSupportedException(); }
        }
    }
    private sealed class LookupDataRefusal : UnauthorizedAccessException
    {
        private readonly IDictionary values;
        internal LookupDataRefusal(IDictionary data) : base("WRONG_FILE_OWNER") { values = data; }
        public override IDictionary Data { get { return values; } }
    }
    private sealed class DataGetterRefusal : UnauthorizedAccessException
    {
        internal DataGetterRefusal() : base("WRONG_FILE_OWNER") { }
        public override IDictionary Data { get { throw new InvalidOperationException(); } }
    }
    private sealed class NonOwnerDataGetterFailure : Exception
    {
        public override IDictionary Data { get { throw new InvalidOperationException(); } }
    }

    private static void Require(bool value)
    {
        if (!value) throw new InvalidOperationException();
    }

    private static int Main(string[] args)
    {
        string dataPolicy = "not-checked";
        string phase = "arguments";
        try
        {
            if (args.Length != 0) throw new ArgumentException();
            phase = "runtime-data-policy";
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
            phase = "typed-evidence-attachment";
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
            phase = "typed-evidence-format";
            Require((string)format.Invoke(null, new object[] { error }) == expected);
            phase = "typed-evidence-read";
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(error) == frame);

            // This tests the Framework storage requirement behaviorally even
            // where Exception.Data itself no longer checks Type.IsSerializable.
            Exception restored;
            phase = "typed-evidence-roundtrip";
            using (var stream = new MemoryStream())
            {
                var formatter = new BinaryFormatter();
                formatter.Serialize(stream, error);
                stream.Position = 0;
                restored = (Exception)formatter.Deserialize(stream);
            }
            phase = "restored-evidence-format";
            Require((string)format.Invoke(null, new object[] { restored }) == expected);
            phase = "restored-evidence-read";
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(restored) == frame);

            phase = "arbitrary-data-exclusion";
            var arbitrary = new UnauthorizedAccessException("WRONG_FILE_OWNER");
            arbitrary.Data["CodexManage.OwnerEvidence"] = "fixture-not-exported";
            Require((string)format.Invoke(null, new object[] { arbitrary }) ==
                "UNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException:owner-check");
            Require(attach != null);
            phase = "missing-key-evidence-read";
            var absent = new LookupDataRefusal(new MissingKeyData());
            attach.Invoke(null, new object[] { absent, frame });
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(absent) == null);
            Require(absent.Message == "WRONG_FILE_OWNER");
            phase = "readonly-evidence-attachment";
            var readOnly = new ReadOnlyDataRefusal();
            attach.Invoke(null, new object[] { readOnly, frame });
            Require(readOnly.Message == "WRONG_FILE_OWNER");
            phase = "readonly-evidence-read";
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(readOnly) == null);
            phase = "data-getter-failure";
            var unreadable = new DataGetterRefusal();
            attach.Invoke(null, new object[] { unreadable, frame });
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(unreadable) == null);
            Require(unreadable.Message == "WRONG_FILE_OWNER");
            foreach (bool contains in new[] { true, false })
            {
                phase = contains ? "data-contains-failure" : "data-indexer-failure";
                var failed = new LookupDataRefusal(new FailedLookupData(contains));
                attach.Invoke(null, new object[] { failed, frame });
                Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(failed) == null);
                Require(failed.Message == "WRONG_FILE_OWNER");
            }
            phase = "irrelevant-data-not-read";
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(new NonOwnerDataGetterFailure()) == null);
            Require(CodexManagePrivateStateSecurity.OwnerFailureEvidence(null) == null);
            phase = "complete";
            Console.WriteLine("{\"kind\":\"managed-owner-evidence\",\"passed\":true," +
                "\"phase\":\"" + phase + "\",\"exceptionDataPolicy\":\"" + dataPolicy + "\",\"frameRoundTrip\":true," +
                "\"arbitraryDataExcluded\":true,\"attachmentFailurePreservesRefusal\":true," +
                "\"optionalDataLookupProtected\":true,\"irrelevantDataExcluded\":true," +
                "\"windowsPInvokeExecuted\":false}");
            return 0;
        }
        catch (Exception error)
        {
            while (error is TargetInvocationException && error.InnerException != null)
                error = error.InnerException;
            Console.WriteLine("{\"kind\":\"managed-owner-evidence\",\"passed\":false," +
                "\"phase\":\"" + phase + "\",\"exceptionDataPolicy\":\"" + dataPolicy + "\",\"failureType\":\"" +
                error.GetType().Name + "\",\"windowsPInvokeExecuted\":false}");
            return 1;
        }
    }
}
