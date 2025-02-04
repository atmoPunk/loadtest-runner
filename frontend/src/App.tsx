import './App.css'
import '@mantine/core/styles.css'

import { MantineProvider, TextInput, NumberInput, Button, Text, Divider, Container, NativeSelect, Anchor, List } from '@mantine/core'

import { useForm } from '@mantine/form'
import { useCallback, useEffect } from 'react'
import { atom } from 'nanostores'
import { useStore } from '@nanostores/react'

type TaskType = 'replication' | 'sharding';

interface FormData {
    image: string;
    nodeCount: number;
    task: TaskType;
}

interface VM {
    type: string;
    instance: string
}

interface TestData {
    uuid: string,
    state: string,
    client: VM | null,
    nodes: VM[],
}

export const $currentTest = atom<TestData | null>(null);
export const $logUrls = atom<string[]>([]);

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

    const logAnchors = logUrls.map((url) => {
        const [instance, cmdFull] = url.split('?')[0].split('/').slice(5);
        const cmd = cmdFull.split('-', 1)[0];
        const ext = cmdFull.slice(-8);

        return <Anchor href={url}>{`${instance}/${cmd}${ext}`}</Anchor>
    });

    return <Container>
        <Text>
            { JSON.stringify(currentTest) }
        </Text>
        <Divider />
        <List>
            { logAnchors.map((a) => {
                return <List.Item>{a}</List.Item>
            }) }
        </List>
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
        const response = await fetch(`/api/test?image=${formData.image}&node_count=${formData.nodeCount}`, {
            method: 'POST',
        });
        const json = await response.json();
        console.log(json);
        $currentTest.set(json);
    }, []);

  return (
    <MantineProvider>
        <h1>Hello, world!</h1>
        <form onSubmit={testForm.onSubmit(onSubmit)}>
            <TextInput {...testForm.getInputProps('image')} />
            <NumberInput {...testForm.getInputProps('nodeCount')} />
            <NativeSelect data={['replication', 'sharding'] as const} {...testForm.getInputProps('task')} />
            <Button type="submit">Launch</Button>
        </form>
        <CurrentTest/>
    </MantineProvider>
  )
}

export default App
