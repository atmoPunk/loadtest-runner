import './App.css'
import '@mantine/core/styles.css'

import { MantineProvider, TextInput, NumberInput, Button, Text, Divider, Container, NativeSelect, Anchor, Stack, Badge, Group, Title } from '@mantine/core'

import { useForm } from '@mantine/form'
import { useCallback, useEffect } from 'react'
import { atom } from 'nanostores'
import { useStore } from '@nanostores/react'
import { persistentAtom } from '@nanostores/persistent'

type TaskType = 'replication' | 'sharding-naive' | 'sharding-linear' | 'sharding-consistent';

interface FormData {
    image: string
    nodeCount: number
    task: TaskType
}

interface VM {
    type: string
    instance: string
    ip: string
}

interface TestData {
    uuid: string
    state: string
    client: VM | null
    nodes: VM[]
    leaderInstance: string | null
}

interface LogUrls {
    stdout: string
    stderr: string
}

export const $currentTest = persistentAtom<TestData | null>("currentTest", null, {
    encode: JSON.stringify,
    decode: JSON.parse
});

export const $logUrls = atom<string[]>([]);

function VMText(vm: VM, logUrls: Map<string, LogUrls>, isLeader: boolean) {
    let outLog = null;
    let errLog = null;
    if (logUrls.has(vm.instance)) {
        const urls = logUrls.get(vm.instance)!!;
        outLog = <Anchor href={urls.stdout}>stdout</Anchor>
        errLog = <Anchor href={urls.stderr}>stderr</Anchor>
    }
    return <Text>
        { vm.instance }{ isLeader ? <Text c="red" span inherit>*</Text> : null} ({ vm.ip }) { outLog } { errLog }
    </Text>
}

function CurrentTest() {
    const currentTest = useStore($currentTest);
    const logUrls = useStore($logUrls);

    const fetchUpdate = useCallback(async () => {
        const response = await fetch(`/api/test/${currentTest!.uuid}`);
        const json = await response.json();
        $currentTest.set(json);
    }, [currentTest]);

    const fetchLogs = useCallback(async() => {
        const response = await fetch(`/api/logs/${currentTest!.uuid}`);
        const json = await response.json();
        $logUrls.set(json.urls);
    }, [currentTest]);

    useEffect(() => {
        if (currentTest === null) {
            return;
        }
        if (currentTest.state === 'SETUP' || currentTest.state === 'RUNNING') {
            setTimeout(fetchUpdate, 5000);
        }
    }, [currentTest, fetchUpdate]);

    useEffect(() => {
        if (currentTest === null) {
            return;
        }
        if (currentTest.state === 'FINISHED' || currentTest.state === 'FAILURE') {
            fetchLogs();
        }
    }, [currentTest, fetchLogs])

    if (currentTest === null) {
        return null;
    }

    const lUrls = new Map<string, LogUrls>();
    logUrls.forEach((url) => {
        const [instance, cmdFull] = url.split('?')[0].split('/').slice(5);
        const ext = cmdFull.slice(-7, -4);
        const logUrls: LogUrls = lUrls.get(instance) ?? {stdout: '', stderr: ''};
        if (ext == 'out') {
            logUrls.stdout = url;
        } else {
            logUrls.stderr = url;
        }
        lUrls.set(instance, logUrls);
    })

    const nodeItems = currentTest.nodes.map((vm) => {
        const isLeader = currentTest.leaderInstance ? (vm.instance == currentTest.leaderInstance) : false;
        return VMText(vm, lUrls, isLeader);
    })

    let stateBadge = null;
    switch (currentTest.state) {
        case 'SETUP':
            stateBadge = <Badge color="yellow">Setup</Badge>
            break;
        case 'RUNNING':
            stateBadge = <Badge color="blue">Running</Badge>
            break;
        case 'FAILURE':
            stateBadge = <Badge color="red">Failure</Badge>
            break;
        case 'FINISHED':
            stateBadge = <Badge color="green">Finished</Badge>
            break;
    }

    return <Container ta="left">
        <Group><Title order={6}>Test: { currentTest.uuid }</Title> { stateBadge }</Group>
        { currentTest.client ? VMText(currentTest.client, lUrls, false) : null }
        { nodeItems }
    </Container>;
}

function App() {
    const testForm = useForm<FormData>({
        initialValues: {
            image: 'ghcr.io/bdse-class-2024/kvnode:51bee0409',
            nodeCount: 1,
            task: 'replication'
        }
    });

    const onSubmit = useCallback(async (formData: FormData) => {
        console.log(formData);
        const response = await fetch(`/api/test?image=${formData.image}&node_count=${formData.nodeCount}&task_type=${formData.task}`, {
            method: 'POST',
        });
        const json = await response.json();
        console.log(json);
        $currentTest.set(json);
        $logUrls.set([]);
    }, []);

    const taskTypes = [
        {label: 'Replication', value: 'replication'},
        {label: 'Sharding (naive)', value: 'sharding-naive'},
        {label: 'Sharding (linear)', value: 'sharding-linear'},
        {label: 'Sharding (consistent hashing)', value: 'sharding-consistent'},
    ]

  return (
    <MantineProvider>
        <Stack>
            <Title order={1}>Kvas Loadtest Runner</Title>
            <Divider />
            <form onSubmit={testForm.onSubmit(onSubmit)}>
                <Stack>
                    <TextInput label="Image" {...testForm.getInputProps('image')} />
                    <NumberInput label="Node count" min={1} max={7} {...testForm.getInputProps('nodeCount')} />
                    <NativeSelect label="Task type" data={taskTypes} {...testForm.getInputProps('task')} />
                    <Button type="submit">Launch</Button>
                </Stack>
            </form>
            <Divider />
            <CurrentTest/>
        </Stack>
    </MantineProvider>
  )
}

export default App
